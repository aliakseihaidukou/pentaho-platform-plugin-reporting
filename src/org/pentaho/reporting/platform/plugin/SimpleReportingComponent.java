package org.pentaho.reporting.platform.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Array;
import java.net.URL;
import java.util.Date;
import java.util.Map;

import javax.print.DocFlavor;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.swing.table.TableModel;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.pentaho.commons.connection.IPentahoResultSet;
import org.pentaho.platform.api.engine.IAcceptsRuntimeInputs;
import org.pentaho.platform.api.engine.IActionSequenceResource;
import org.pentaho.platform.api.engine.IPentahoSession;
import org.pentaho.platform.api.engine.IStreamingPojo;
import org.pentaho.platform.api.repository.IContentRepository;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.pentaho.platform.plugin.action.jfreereport.helper.PentahoTableModel;
import org.pentaho.platform.util.web.MimeHelper;
import org.pentaho.reporting.engine.classic.core.AttributeNames;
import org.pentaho.reporting.engine.classic.core.ClassicEngineBoot;
import org.pentaho.reporting.engine.classic.core.MasterReport;
import org.pentaho.reporting.engine.classic.core.modules.output.pageable.pdf.PdfPageableModule;
import org.pentaho.reporting.engine.classic.core.modules.output.table.csv.CSVTableModule;
import org.pentaho.reporting.engine.classic.core.modules.output.table.html.HtmlTableModule;
import org.pentaho.reporting.engine.classic.core.modules.output.table.rtf.RTFTableModule;
import org.pentaho.reporting.engine.classic.core.modules.output.table.xls.ExcelTableModule;
import org.pentaho.reporting.engine.classic.core.modules.parser.base.ReportGenerator;
import org.pentaho.reporting.engine.classic.core.parameters.DefaultParameterContext;
import org.pentaho.reporting.engine.classic.core.parameters.ListParameter;
import org.pentaho.reporting.engine.classic.core.parameters.ParameterContext;
import org.pentaho.reporting.engine.classic.core.parameters.ParameterDefinitionEntry;
import org.pentaho.reporting.engine.classic.core.util.beans.BeanException;
import org.pentaho.reporting.engine.classic.core.util.beans.ConverterRegistry;
import org.pentaho.reporting.engine.classic.core.util.beans.ValueConverter;
import org.pentaho.reporting.engine.classic.extensions.modules.java14print.Java14PrintUtil;
import org.pentaho.reporting.libraries.base.util.StringUtils;
import org.pentaho.reporting.libraries.resourceloader.ResourceException;
import org.pentaho.reporting.platform.plugin.messages.Messages;
import org.pentaho.reporting.platform.plugin.output.CSVOutput;
import org.pentaho.reporting.platform.plugin.output.HTMLOutput;
import org.pentaho.reporting.platform.plugin.output.PDFOutput;
import org.pentaho.reporting.platform.plugin.output.PageableHTMLOutput;
import org.pentaho.reporting.platform.plugin.output.RTFOutput;
import org.pentaho.reporting.platform.plugin.output.XLSOutput;
import org.pentaho.reporting.platform.plugin.output.EmailOutput;
import org.xml.sax.InputSource;

public class SimpleReportingComponent implements IStreamingPojo, IAcceptsRuntimeInputs
{

  /**
   * The logging for logging messages from this component
   */
  private static final Log log = LogFactory.getLog(SimpleReportingComponent.class);

  public static final String OUTPUT_TYPE = "output-type"; //$NON-NLS-1$
  public static final String MIME_TYPE_HTML = "text/html"; //$NON-NLS-1$
  public static final String MIME_TYPE_EMAIL = "mime-message/text/html"; //$NON-NLS-1$
  public static final String MIME_TYPE_PDF = "application/pdf"; //$NON-NLS-1$
  public static final String MIME_TYPE_XLS = "application/vnd.ms-excel"; //$NON-NLS-1$
  public static final String MIME_TYPE_RTF = "application/rtf"; //$NON-NLS-1$
  public static final String MIME_TYPE_CSV = "text/csv"; //$NON-NLS-1$

  public static final String XLS_WORKBOOK_PARAM = "workbook"; //$NON-NLS-1$

  public static final String REPORTLOAD_RESURL = "res-url"; //$NON-NLS-1$
  public static final String REPORT_DEFINITION_INPUT = "report-definition"; //$NON-NLS-1$
  public static final String USE_CONTENT_REPOSITORY = "useContentRepository"; //$NON-NLS-1$
  public static final String REPORTHTML_CONTENTHANDLER_PATTERN = "content-handler-pattern"; //$NON-NLS-1$
  public static final String REPORTGENERATE_YIELDRATE = "yield-rate"; //$NON-NLS-1$
  public static final String ACCEPTED_PAGE = "accepted-page"; //$NON-NLS-1$
  public static final String PAGINATE_OUTPUT = "paginate"; //$NON-NLS-1$
  public static final String PRINT = "print"; //$NON-NLS-1$
  public static final String PRINTER_NAME = "printer-name"; //$NON-NLS-1$

  /**
   * Static initializer block to guarantee that the ReportingComponent will be in a state where the reporting engine will be booted. We have a system listener
   * which will boot the reporting engine as well, but we do not want to solely rely on users having this setup correctly. The errors you receive if the engine
   * is not booted are not very helpful, especially to outsiders, so we are trying to provide multiple paths to success. Enjoy.
   */
  static
  {
    final ReportingSystemStartupListener startup = new ReportingSystemStartupListener();
    startup.startup(null);
  }

  /**
   * The output-type for the generated report, such as PDF, XLS, CSV, HTML, etc This must be the mime-type!
   */
  private String outputType;
  private String outputTarget;
  private MasterReport report;
  private Map<String, Object> inputs;
  private OutputStream outputStream;
  private InputStream reportDefinitionInputStream;
  private Boolean useContentRepository = Boolean.FALSE;
  private IActionSequenceResource reportDefinition;
  private String reportDefinitionPath;
  private IPentahoSession session;
  private boolean paginateOutput = false;
  private int acceptedPage = -1;
  private int pageCount = -1;

  /*
   * These fields are for enabling printing
   */
  private boolean print = false;
  private String printer;

  /*
   * Default constructor
   */
  public SimpleReportingComponent()
  {
  }

  // ----------------------------------------------------------------------------
  // BEGIN BEAN METHODS
  // ----------------------------------------------------------------------------

  public String getOutputTarget()
  {
    return outputTarget;
  }

  public void setOutputTarget(final String outputTarget)
  {
    this.outputTarget = outputTarget;
  }

  /**
   * Sets the mime-type for determining which report output type to generate. This should be a mime-type for consistency with streaming output mime-types.
   * 
   * @param outputType
   *          the desired output type (mime-type) for the report engine to generate
   */
  public void setOutputType(final String outputType)
  {
    this.outputType = outputType;
  }

  /**
   * Gets the output type, this should be a mime-type for consistency with streaming output mime-types.
   * 
   * @return the current output type for the report
   */
  public String getOutputType()
  {
    return outputType;
  }

  /**
   * This method returns the resource for the report-definition, if available.
   * 
   * @return the report-definition resource
   */
  public IActionSequenceResource getReportDefinition()
  {
    return reportDefinition;
  }

  /**
   * Sets the report-definition if it is provided to us by way of an action-sequence resource. The name must be reportDefinition or report-definition.
   * 
   * @param reportDefinition
   *          a report-definition as seen (wrapped) by an action-sequence
   */
  public void setReportDefinition(final IActionSequenceResource reportDefinition)
  {
    this.reportDefinition = reportDefinition;
  }

  /**
   * This method will be called if an input is called reportDefinitionInputStream, or any variant of that with dashes report-definition-inputstream for example.
   * The primary purpose of this method is to facilitate unit testing.
   * 
   * @param reportDefinitionInputStream
   *          any kind of InputStream which contains a valid report-definition
   */
  public void setReportDefinitionInputStream(final InputStream reportDefinitionInputStream)
  {
    this.reportDefinitionInputStream = reportDefinitionInputStream;
  }

  /**
   * Returns the path to the report definition (for platform use this is a path in the solution repository)
   * 
   * @return reportdefinitionPath
   */
  public String getReportDefinitionPath()
  {
    return reportDefinitionPath;
  }

  /**
   * Sets the path to the report definition (platform path)
   * 
   * @param reportDefinitionPath
   */
  public void setReportDefinitionPath(final String reportDefinitionPath)
  {
    this.reportDefinitionPath = reportDefinitionPath;
  }

  /**
   * Returns true if the report engine will be asked to use a paginated (HTML) output processor
   * 
   * @return paginated
   */
  public boolean isPaginateOutput()
  {
    return paginateOutput;
  }

  /**
   * Set the paging mode used by the reporting engine. This will also be set if an input
   * 
   * @param paginateOutput
   *          page mode
   */
  public void setPaginateOutput(final boolean paginateOutput)
  {
    this.paginateOutput = paginateOutput;
  }

  public int getAcceptedPage()
  {
    return acceptedPage;
  }

  public void setAcceptedPage(final int acceptedPage)
  {
    this.acceptedPage = acceptedPage;
  }

  /**
   * This method sets the IPentahoSession to use in order to access the pentaho platform file repository and content repository.
   * 
   * @param session
   *          a valid pentaho session
   */
  public void setSession(final IPentahoSession session)
  {
    this.session = session;
  }

  /**
   * This method returns the output-type for the streaming output, it is the same as what is returned by getOutputType() for consistency.
   * 
   * @return the mime-type for the streaming output
   */
  public String getMimeType()
  {
    return outputType;
  }

  /**
   * This method sets the OutputStream to write streaming content on.
   * 
   * @param outputStream
   *          an OutputStream to write to
   */
  public void setOutputStream(final OutputStream outputStream)
  {
    this.outputStream = outputStream;
  }

  public void setUseContentRepository(final Boolean useContentRepository)
  {
    this.useContentRepository = useContentRepository;
  }

  /**
   * This method checks if the output is targeting a printer
   * 
   * @return true if the output is supposed to go to a printer
   */
  public boolean isPrint()
  {
    return print;
  }

  /**
   * Set whether or not to send the report to a printer
   * 
   * @param print
   */
  public void setPrint(boolean print)
  {
    this.print = print;
  }

  /**
   * This method gets the name of the printer the report will be sent to
   * 
   * @return the name of the printer that the report will be sent to
   */
  public String getPrinter()
  {
    return printer;
  }

  /**
   * Set the name of the printer to send the report to
   * 
   * @param printer
   *          the name of the printer that the report will be sent to, a null value will be interpreted as the default printer
   */
  public void setPrinter(String printer)
  {
    this.printer = printer;
  }

  /**
   * This method sets the map of *all* the inputs which are available to this component. This allows us to use action-sequence inputs as parameters for our
   * reports.
   * 
   * @param inputs
   *          a Map containing inputs
   */
  public void setInputs(final Map<String, Object> inputs)
  {
    this.inputs = inputs;
    if (inputs.containsKey(REPORT_DEFINITION_INPUT))
    {
      setReportDefinitionInputStream((InputStream) inputs.get(REPORT_DEFINITION_INPUT));
    }
    if (inputs.containsKey(USE_CONTENT_REPOSITORY))
    {
      setUseContentRepository((Boolean) inputs.get(USE_CONTENT_REPOSITORY));
    }
    if (inputs.containsKey(PAGINATE_OUTPUT))
    {
      paginateOutput = "true".equalsIgnoreCase("" + inputs.get(PAGINATE_OUTPUT)); //$NON-NLS-1$ //$NON-NLS-2$
      if (paginateOutput && inputs.containsKey(ACCEPTED_PAGE))
      {
        acceptedPage = Integer.parseInt("" + inputs.get(ACCEPTED_PAGE)); //$NON-NLS-1$
      }
    }
    if (inputs.containsKey(PRINT))
    {
      print = "true".equalsIgnoreCase("" + inputs.get(PRINT)); //$NON-NLS-1$ //$NON-NLS-2$
    }
    if (inputs.containsKey(PRINTER_NAME))
    {
      printer = "" + inputs.get(PRINTER_NAME);
    }
  }

  // ----------------------------------------------------------------------------
  // END BEAN METHODS
  // ----------------------------------------------------------------------------

  protected Object getInput(final String key, final Object defaultValue)
  {
    if (inputs != null)
    {
      final Object input = inputs.get(key);
      if (input != null)
      {
        return input;
      }
    }
    return defaultValue;
  }

  /**
   * Get the MasterReport for the report-definition, the MasterReport object will be cached as needed, using the PentahoResourceLoader.
   * 
   * @return a parsed MasterReport object
   * @throws ResourceException
   * @throws IOException
   */
  public MasterReport getReport() throws ResourceException, IOException
  {
    if (report == null)
    {
      if (reportDefinitionInputStream != null)
      {
        final ReportGenerator generator = ReportGenerator.createInstance();
        final InputSource repDefInputSource = new InputSource(reportDefinitionInputStream);
        report = generator.parseReport(repDefInputSource, getDefinedResourceURL(null));
      } else
        if (reportDefinition != null)
        {
          // load the report definition as an action-sequence resource
          report = ReportCreator.createReport(reportDefinition.getAddress(), session);
        } else
        {
          report = ReportCreator.createReport(reportDefinitionPath, session);
        }
      report.setReportEnvironment(new PentahoReportEnvironment(report.getConfiguration()));
    }

    return report;
  }

  private String computeEffectiveOutputTarget(final MasterReport report)
  {
    if (Boolean.TRUE.equals(report.getAttribute(AttributeNames.Core.NAMESPACE, AttributeNames.Core.LOCK_PREFERRED_OUTPUT_TYPE)))
    {
      final Object preferredOutputType = report.getAttribute(AttributeNames.Core.NAMESPACE, AttributeNames.Core.PREFERRED_OUTPUT_TYPE);
      if (preferredOutputType != null)
      {
        outputType = MimeHelper.getMimeTypeFromExtension("." + String.valueOf(preferredOutputType)); //$NON-NLS-1$
        if (StringUtils.isEmpty(outputType))
        {
          outputType = String.valueOf(preferredOutputType);
        }
      }
    }

    if (outputTarget != null)
    {
      // if a engine-level output target is given, use it as it is. We can assume that the user knows how to
      // map from that to a real mime-type.
      return outputTarget;
    }

    // if the user has given a mime-type instead of a output-target, lets map it to the "best" choice. If the
    // user wanted full control, he would have used the output-target property instead.
    if (MIME_TYPE_CSV.equals(outputType))
    {
      return CSVTableModule.TABLE_CSV_STREAM_EXPORT_TYPE;
    }
    if (MIME_TYPE_HTML.equals(outputType))
    {
      if (isPaginateOutput())
      {
        return HtmlTableModule.TABLE_HTML_PAGE_EXPORT_TYPE;
      }
      return HtmlTableModule.TABLE_HTML_STREAM_EXPORT_TYPE;
    }
    if (MIME_TYPE_PDF.equals(outputType))
    {
      return PdfPageableModule.PDF_EXPORT_TYPE;
    }
    if (MIME_TYPE_RTF.equals(outputType))
    {
      return RTFTableModule.TABLE_RTF_FLOW_EXPORT_TYPE;
    }
    if (MIME_TYPE_XLS.equals(outputType))
    {
      return ExcelTableModule.EXCEL_FLOW_EXPORT_TYPE;
    }
    if (MIME_TYPE_EMAIL.equals(outputType))
    {
      return MIME_TYPE_EMAIL;
    }

    // if nothing is specified explicity, we may as well ask the report what it prefers..
    final Object preferredOutputType = report.getAttribute(AttributeNames.Core.NAMESPACE, AttributeNames.Core.PREFERRED_OUTPUT_TYPE);
    if (preferredOutputType != null)
    {
      return String.valueOf(preferredOutputType);
    }
    // default to HTML stream ..
    return HtmlTableModule.TABLE_HTML_STREAM_EXPORT_TYPE;
  }

  /**
   * Apply inputs (if any) to corresponding report parameters, care is taken when checking parameter types to perform any necessary casting and conversion.
   * 
   * @param report
   *          a MasterReport object to apply parameters to
   * @param context
   *          a ParameterContext for which the parameters will be under
   */
  public void applyInputsToReportParameters(final MasterReport report, final ParameterContext context)
  {
    // apply inputs to report
    if (inputs != null)
    {
      final ParameterDefinitionEntry[] params = report.getParameterDefinition().getParameterDefinitions();
      for (final ParameterDefinitionEntry param : params)
      {
        final String paramName = param.getName();
        Object value = inputs.get(paramName);
        final Object defaultValue = param.getDefaultValue(context);
        if (value == null && defaultValue != null)
        {
          value = defaultValue;
        }
        if (value != null)
        {
          addParameter(report, param, paramName, value);
        }
      }
    }
  }

  private Object convert(final Class targetType, final Object rawValue) throws NumberFormatException
  {
    if (targetType == null)
    {
      throw new NullPointerException();
    }

    if (rawValue == null)
    {
      return null;
    }
    if (targetType.isInstance(rawValue))
    {
      return rawValue;
    }

    if (targetType.isAssignableFrom(TableModel.class) && IPentahoResultSet.class.isAssignableFrom(rawValue.getClass()))
    {
      // wrap IPentahoResultSet to simulate TableModel
      return new PentahoTableModel((IPentahoResultSet) rawValue);
    }

    final String valueAsString = String.valueOf(rawValue);
    if (StringUtils.isEmpty(valueAsString))
    {
      return null;
    }

    if (targetType.equals(Date.class))
    {
      try
      {
        return new Date(new Long(valueAsString));
      } catch (NumberFormatException nfe)
      {
        // ignore, we try to parse it as real date now ..
      }
    }

    final ValueConverter valueConverter = ConverterRegistry.getInstance().getValueConverter(targetType);
    if (valueConverter != null)
    {
      try
      {
        return valueConverter.toPropertyValue(valueAsString);
      } catch (BeanException e)
      {
        throw new RuntimeException(Messages.getInstance().getString("ReportPlugin.unableToConvertParameter")); //$NON-NLS-1$
      }
    }
    return rawValue;
  }

  private void addParameter(final MasterReport report, final ParameterDefinitionEntry param, final String key, final Object value)
  {
    if (value.getClass().isArray())
    {
      final Class componentType;
      if (param.getValueType().isArray())
      {
        componentType = param.getValueType().getComponentType();
      } else
      {
        componentType = param.getValueType();
      }

      final int length = Array.getLength(value);
      final Object array = Array.newInstance(componentType, length);
      for (int i = 0; i < length; i++)
      {
        Array.set(array, i, convert(componentType, Array.get(value, i)));
      }
      report.getParameterValues().put(key, array);
    } else
      if (isAllowMultiSelect(param))
      {
        // if the parameter allows multi selections, wrap this single input in an array
        // and re-call addParameter with it
        final Object[] array = new Object[1];
        array[0] = value;
        addParameter(report, param, key, array);
      } else
      {
        report.getParameterValues().put(key, convert(param.getValueType(), value));
      }
  }

  private boolean isAllowMultiSelect(final ParameterDefinitionEntry parameter)
  {
    if (parameter instanceof ListParameter)
    {
      return ((ListParameter) parameter).isAllowMultiSelection();
    }
    return false;
  }

  private URL getDefinedResourceURL(final URL defaultValue)
  {
    if (inputs == null || inputs.containsKey(REPORTLOAD_RESURL) == false)
    {
      return defaultValue;
    }

    try
    {
      final String inputStringValue = (String) getInput(REPORTLOAD_RESURL, null);
      return new URL(inputStringValue);
    } catch (Exception e)
    {
      return defaultValue;
    }
  }

  protected int getYieldRate()
  {
    if (getInput(REPORTGENERATE_YIELDRATE, null) != null)
    {
      final Object inputValue = inputs.get(REPORTGENERATE_YIELDRATE);
      if (inputValue instanceof Number)
      {
        final Number n = (Number) inputValue;
        if (n.intValue() < 1)
        {
          return 0;
        }
        return n.intValue();
      }
    }
    return 0;
  }

  /**
   * This method returns the number of logical pages which make up the report. This results of this method are available only after validate/execute have been
   * successfully called. This field has no setter, as it should never be set by users.
   * 
   * @return the number of logical pages in the report
   */
  public int getPageCount()
  {
    return pageCount;
  }

  /**
   * This method will determine if the component instance 'is valid.' The validate() is called after all of the bean 'setters' have been called, so we may
   * validate on the actual values, not just the presence of inputs as we were historically accustomed to.
   * <p/>
   * Since we should have a list of all action-sequence inputs, we can determine if we have sufficient inputs to meet the parameter requirements of the
   * report-definition. This would include validation of values and ranges of values.
   * 
   * @return true if valid
   * @throws Exception
   */
  public boolean validate() throws Exception
  {
    if (reportDefinition == null && reportDefinitionInputStream == null && reportDefinitionPath == null)
    {
      log.error(Messages.getInstance().getString("ReportPlugin.reportDefinitionNotProvided")); //$NON-NLS-1$
      return false;
    }
    if (reportDefinition != null && reportDefinitionPath != null && session == null)
    {
      log.error(Messages.getInstance().getString("ReportPlugin.noUserSession")); //$NON-NLS-1$
      return false;
    }
    if (outputStream == null && print == false)
    {
      log.error(Messages.getInstance().getString("ReportPlugin.outputStreamRequired")); //$NON-NLS-1$
      return false;
    }
    return true;
  }

  /**
   * Perform the primary function of this component, this is, to execute. This method will be invoked immediately following a successful validate().
   * 
   * @return true if successful execution
   * @throws Exception
   */
  public boolean execute() throws Exception
  {
    final MasterReport report = getReport();

    try
    {
      final ParameterContext parameterContext = new DefaultParameterContext(report);
      // open parameter context
      parameterContext.open();
      applyInputsToReportParameters(report, parameterContext);
      parameterContext.close();

      if (isPrint())
      {
        // handle printing
        // basic logic here is: get the default printer, attempt to resolve the user specified printer, default back as needed
        PrintService printService = PrintServiceLookup.lookupDefaultPrintService();
        if (StringUtils.isEmpty(getPrinter()) == false)
        {
          PrintService[] services = PrintServiceLookup.lookupPrintServices(DocFlavor.SERVICE_FORMATTED.PAGEABLE, null);
          for (final PrintService service : services)
          {
            if (service.getName().equals(printer))
            {
              printService = service;
            }
          }
          if ((printer == null) && (services.length > 0))
          {
            printService = services[0];
          }
        }
        Java14PrintUtil.printDirectly(report, printService);
      } else
      {
        final String outputType = computeEffectiveOutputTarget(report);
        if (HtmlTableModule.TABLE_HTML_PAGE_EXPORT_TYPE.equals(outputType))
        {
          String contentHandlerPattern = (String) getInput(REPORTHTML_CONTENTHANDLER_PATTERN, ClassicEngineBoot.getInstance().getGlobalConfig()
              .getConfigProperty("org.pentaho.web.ContentHandler")); //$NON-NLS-1$
          if (useContentRepository)
          {
            // use the content repository
            contentHandlerPattern = (String) getInput(REPORTHTML_CONTENTHANDLER_PATTERN, ClassicEngineBoot.getInstance().getGlobalConfig().getConfigProperty(
                "org.pentaho.web.resource.ContentHandler")); //$NON-NLS-1$
            final IContentRepository contentRepository = PentahoSystem.get(IContentRepository.class, session);
            pageCount = PageableHTMLOutput.generate(session, report, acceptedPage, outputStream, contentRepository, contentHandlerPattern, getYieldRate());
            return true;
          } else
          {
            // don't use the content repository
            pageCount = PageableHTMLOutput.generate(report, acceptedPage, outputStream, contentHandlerPattern, getYieldRate());
            return true;
          }
        }
        if (HtmlTableModule.TABLE_HTML_STREAM_EXPORT_TYPE.equals(outputType))
        {
          String contentHandlerPattern = (String) getInput(REPORTHTML_CONTENTHANDLER_PATTERN, ClassicEngineBoot.getInstance().getGlobalConfig()
              .getConfigProperty("org.pentaho.web.ContentHandler")); //$NON-NLS-1$
          if (useContentRepository)
          {
            // use the content repository
            contentHandlerPattern = (String) getInput(REPORTHTML_CONTENTHANDLER_PATTERN, ClassicEngineBoot.getInstance().getGlobalConfig().getConfigProperty(
                "org.pentaho.web.resource.ContentHandler")); //$NON-NLS-1$
            final IContentRepository contentRepository = PentahoSystem.get(IContentRepository.class, session);
            return HTMLOutput.generate(session, report, outputStream, contentRepository, contentHandlerPattern, getYieldRate());
          } else
          {
            // don't use the content repository
            return HTMLOutput.generate(report, outputStream, contentHandlerPattern, getYieldRate());
          }
        } else
          if (PdfPageableModule.PDF_EXPORT_TYPE.equals(outputType))
          {
            return PDFOutput.generate(report, outputStream, getYieldRate());
          } else
            if (ExcelTableModule.EXCEL_FLOW_EXPORT_TYPE.equals(outputType))
            {
              final InputStream templateInputStream = (InputStream) getInput(XLS_WORKBOOK_PARAM, null);
              return XLSOutput.generate(report, outputStream, templateInputStream, getYieldRate());
            } else
              if (CSVTableModule.TABLE_CSV_STREAM_EXPORT_TYPE.equals(outputType))
              {
                return CSVOutput.generate(report, outputStream, getYieldRate());
              } else
                if (RTFTableModule.TABLE_RTF_FLOW_EXPORT_TYPE.equals(outputType))
                {
                  return RTFOutput.generate(report, outputStream, getYieldRate());
                } else
                  if (MIME_TYPE_EMAIL.equals(outputType))
                  {
                    return EmailOutput.generate(report, outputStream, "cid:{0}", getYieldRate()); //$NON-NLS-1$
                  }
      }
    } catch (Throwable t)
    {
      log.error(Messages.getInstance().getString("ReportPlugin.executionFailed"), t); //$NON-NLS-1$
    }
    // lets not pretend we were successfull, if the export type was not a valid one.
    return false;
  }

}
