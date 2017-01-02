/* Copyright 2012, UCAR/Unidata.
   See the LICENSE file for more information.
*/


package dap4.servlet;

import dap4.core.ce.CEConstraint;
import dap4.core.data.ChecksumMode;
import dap4.core.data.DSP;
import dap4.core.dmr.DapAttribute;
import dap4.core.dmr.DapDataset;
import dap4.core.dmr.DapType;
import dap4.core.dmr.ErrorResponse;
import dap4.core.util.*;
import dap4.dap4lib.*;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.Map;

abstract public class DapController extends HttpServlet
{
    // Provide a way for test programs to pass info into the controller
    static public boolean TESTING = false;

    //////////////////////////////////////////////////
    // Constants

    static public boolean DEBUG = false;
    static public boolean DUMPDMR = false;
    static public boolean DUMPDATA = false;

    static public boolean PARSEDEBUG = false;

    static protected final String BIG_ENDIAN = "Big-Endian";
    static protected final String LITTLE_ENDIAN = "Little-Endian";

    // Is this machine big endian?
    static protected boolean IS_BIG_ENDIAN = (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN);

    static protected final String DMREXT = ".dmr";
    static protected final String DATAEXT = ".dap";
    static protected final String DSREXT = ".dsr";
    static protected final String[] ENDINGS = {DMREXT, DATAEXT, DSREXT};

    static protected final String FAVICON = "favicon.ico"; // relative to resource dir

    static public final long DEFAULTBINARYWRITELIMIT = 100 * 1000000; // in bytes

    //////////////////////////////////////////////////
    // static variables

    // We need a singleton instance of a DapCache in order
    // To avoid re-opening the same NetcdfFile instance.
    // Assume:
    // 1. This is subclassed only once and that class will fill in
    //    this DapCache instance.

    static protected DapCache cache = null;

    //////////////////////////////////////////////////
    // Static accessors

    static protected void
    setCache(DapCache cache)
    {
        DapController.cache = cache;
    }

    static protected DapCache
    getCache()
    {
        return DapController.cache;
    }

    //////////////////////////////////////////////////
    // Instance variables

    protected boolean initialized = false; // Was initialize() called?

    transient protected DapContext dapcxt = new DapContext();

    transient protected DapRequest daprequest = null;

    protected boolean compress = true;

    transient protected DapDSR dsrbuilder = new DapDSR();

    protected ByteOrder order = null;
    protected ChecksumMode checksummode = ChecksumMode.DAP;

    //////////////////////////////////////////////////
    // Constructor(s)

    public DapController()
    {
        ChunkWriter.DUMPDATA = DUMPDATA; // pass it on
    }

    //////////////////////////////////////////////////////////
    // Abstract methods

    /**
     * Process a favicon request.
     *
     * @param icopath The path to the icon
     * @param cxt     The dap context
     */

    abstract protected void doFavicon(String icopath, DapContext cxt) throws IOException;

    /**
     * Process a capabilities request.
     * Currently, does nothing (but see D4TSServlet.doCapabilities).
     *
     * @param cxt The dapontext
     */

    abstract protected void doCapabilities(DapContext cxt) throws IOException;

    /**
     * Convert a URL path into an absolute file path
     * Note that it is assumed than any leading servlet prefix has been removed.
     *
     * @param drq      dap request
     * @param location suffix of url path
     * @return
     * @throws IOException
     */

    abstract public String getResourcePath(DapRequest drq, String location) throws DapException;

    /**
     * Get the maximum # of bytes per request
     *
     * @return size
     */
    abstract public long getBinaryWriteLimit();

    /**
     * Get the servlet name (with no leading or trailing slashes)
     *
     * @return name
     */
    abstract public String getServletID();

    //////////////////////////////////////////////////////////

    public void init()
            throws ServletException
    {
        org.slf4j.Logger logServerStartup = org.slf4j.LoggerFactory.getLogger("serverStartup");
        logServerStartup.info(getClass().getName() + " initialization start");
        try {
            System.setProperty("file.encoding", "UTF-8");
            Field charset = Charset.class.getDeclaredField("defaultCharset");
            charset.setAccessible(true);
            charset.set(null, null);
            initialize();
        } catch (Exception e) {
            throw new ServletException(e);
        }
    }

    /**
     * Initialize servlet/controller
     */
    public void
    initialize()
    {
        this.initialized = true;
    }

    //////////////////////////////////////////////////////////
    // Accessors

    public DapController setControllerPath(String controllerpath)
    {
        this.dapcxt.put("controllerpath", DapUtil.canonjoin("", controllerpath));
        return this;
    }

    public DapContext
    getDapContext()
    {
        return this.dapcxt;
    }

    //////////////////////////////////////////////////////////
    // Primary Controller Entry Point

    public void
    handleRequest(HttpServletRequest req, HttpServletResponse res)
            throws IOException
    {
        DapLog.debug("doGet(): User-Agent = " + req.getHeader("User-Agent"));
        if(!this.initialized) initialize();
        this.daprequest = getRequestState(req, res);
        String url = this.daprequest.getOriginalURL();
        StringBuilder info = new StringBuilder("doGet():");
        info.append(" dataset = ");
        info.append(" url = ");
        info.append(url);
        if(DEBUG) {
            System.err.println("DAP4 Servlet: processing url: " + this.daprequest.getOriginalURL());
        }
        assert (this.dapcxt != null);
        // Add entries to the context
        this.dapcxt.put(HttpServletRequest.class, req);
        this.dapcxt.put(HttpServletResponse.class, res);

        this.order = this.daprequest.getOrder();
        this.checksummode = this.daprequest.getChecksumMode();
        this.dapcxt.put(Dap4Util.DAP4ENDIANTAG, this.order);
        this.dapcxt.put(Dap4Util.DAP4CSUMTAG, this.checksummode);
        // Transfer all other queries
        Map<String, String> queries = this.daprequest.getQueries();
        for(Map.Entry<String, String> entry : queries.entrySet()) {
            if(this.dapcxt.get(entry.getKey()) == null) {
                this.dapcxt.put(entry.getKey(), entry.getValue());
            }
        }

        if(url.endsWith(FAVICON)) {
            doFavicon(FAVICON, this.dapcxt);
            return;
        }

        String datasetpath = DapUtil.nullify(DapUtil.canonicalpath(this.daprequest.getDataset()));
        try {
            if(datasetpath == null) {
                // This is the case where a request was made without a dataset;
                // According to the spec, I think we should return the
                // services/capabilities document
                doCapabilities(this.dapcxt);
            } else {
                RequestMode mode = this.daprequest.getMode();
                if(mode == null)
                    throw new DapException("Unrecognized request extension")
                            .setCode(HttpServletResponse.SC_BAD_REQUEST);
                switch (mode) {
                case DMR:
                    doDMR(this.dapcxt);
                    break;
                case DAP:
                    doData(this.dapcxt);
                    break;
                case DSR:
                    doDSR(this.dapcxt);
                    break;
                default:
                    throw new DapException("Unrecognized request extension")
                            .setCode(HttpServletResponse.SC_BAD_REQUEST);
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
            int code = HttpServletResponse.SC_BAD_REQUEST;
            if(t instanceof DapException) {
                DapException e = (DapException) t;
                code = e.getCode();
                if(code <= 0)
                    code = DapCodes.SC_BAD_REQUEST;
                e.setCode(code);
            } else if(t instanceof FileNotFoundException)
                code = DapCodes.SC_NOT_FOUND;
            else if(t instanceof UnsupportedOperationException)
                code = DapCodes.SC_FORBIDDEN;
            else if(t instanceof MalformedURLException)
                code = DapCodes.SC_NOT_FOUND;
            else if(t instanceof IOException)
                code = DapCodes.SC_BAD_REQUEST;
            else
                code = DapCodes.SC_INTERNAL_SERVER_ERROR;
            senderror(this.daprequest, code, t);
        }//catch
    }

    //////////////////////////////////////////////////////////
    // Extension processors

    /**
     * Process a DSR request.
     * * @param cxt     The dap context
     */

    protected void
    doDSR(DapContext cxt)
            throws IOException
    {
        DapRequest drq = this.daprequest;
        try {
            String dsr = dsrbuilder.generate(drq.getURL());
            OutputStream out = drq.getOutputStream();
            addCommonHeaders(drq);// Add relevant headers
            // Wrap the outputstream with a Chunk writer
            ChunkWriter cw = new ChunkWriter(out, RequestMode.DSR, this.order);
            cw.writeDSR(dsr);
            cw.close();
        } catch (IOException ioe) {
            throw new DapException("DSR generation error", ioe)
                    .setCode(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Process a DMR request.
     *
     * @param cxt The dap context
     */

    protected void
    doDMR(DapContext cxt)
            throws IOException
    {
        DapRequest drq = this.daprequest;

        // Convert the url to an absolute path
        String realpath = getResourcePath(drq, drq.getDatasetPath());

        DSP dsp = DapCache.open(realpath, cxt);
        DapDataset dmr = dsp.getDMR();

        /* Annotate with our endianness */
        addEndianness(dmr, drq);

        // Process any constraint view
        CEConstraint ce = null;
        String sce = drq.queryLookup(DapProtocol.CONSTRAINTTAG);
        ce = CEConstraint.compile(sce, dmr);
        addConstraint(dmr,sce);

        // Provide a PrintWriter for capturing the DMR.
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);

        // Get the DMR as a string
        DMRPrinter dapprinter = new DMRPrinter(dmr, ce, pw, drq.getFormat());
        dapprinter.print();
        pw.close();
        sw.close();

        String sdmr = sw.toString();
        if(DEBUG)
            System.err.println("Sending: DMR:\n" + sdmr);

        addCommonHeaders(drq);// Add relevant headers

        // Wrap the outputstream with a Chunk writer
        OutputStream out = drq.getOutputStream();
        ChunkWriter cw = new ChunkWriter(out, RequestMode.DMR, this.order);
        cw.cacheDMR(sdmr);
        cw.close();
    }

    /**
     * Process a DataDMR request.
     * Note that if this throws an exception,
     * then it has not yet started to output
     * a response. It a response had been initiated,
     * then the exception would produce an error chunk.
     * <p>
     * * @param cxt     The dap context
     */

    protected void
    doData(DapContext cxt)
            throws IOException
    {
        DapRequest drq = this.daprequest;

        // Convert the url to an absolute path
        String realpath = getResourcePath(drq, drq.getDatasetPath());

        DSP dsp = DapCache.open(realpath, cxt);
        if(dsp == null)
            throw new DapException("No such file: " + drq.getResourceRoot());
        DapDataset dmr = dsp.getDMR();
        if(DUMPDMR) {
            printDMR(dmr);
            System.err.println(printDMR(dmr));
            System.err.flush();
        }

        /* Annotate with our endianness */
        addEndianness(dmr, drq);

        // Process any constraint
        CEConstraint ce = null;
        String sce = drq.queryLookup(DapProtocol.CONSTRAINTTAG);
        ce = CEConstraint.compile(sce, dmr);
        addConstraint(dmr,sce);

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);

        // Get the DMR as a string
        DMRPrinter dapprinter = new DMRPrinter(dmr, ce, pw, drq.getFormat());
        dapprinter.print();
        pw.close();
        sw.close();

        String sdmr = sw.toString();
        if(DEBUG || DUMPDMR)
            System.err.println("Sending: Data DMR:\n" + sdmr);

        // Wrap the outputstream with a Chunk writer
        OutputStream out = drq.getOutputStream();
        ChunkWriter cw = new ChunkWriter(out, RequestMode.DAP, this.order);
        cw.setWriteLimit(getBinaryWriteLimit());
        cw.cacheDMR(sdmr);
        cw.flush();

        addCommonHeaders(drq);

        // Dump the databuffer part
        switch (drq.getFormat()) {
        case TEXT:
        case XML:
        case HTML:
            throw new IOException("Unsupported return format: " + drq.getFormat());
            /*
            sw = new StringWriter();
            DAPPrint dp = new DAPPrint(sw);
            dp.print(dsp.getDataset(), ce);
            break;
                */
        case NONE:
        default:
            DapSerializer writer = new DapSerializer(dsp, ce, cw, this.order, drq.getChecksumMode());
            writer.write(dsp.getDMR());
            cw.flush();
            cw.close();
            break;
        }
        // Should we dump data?
        if(DUMPDATA) {
            byte[] data = cw.getDump();
            if(data != null)
                DapDump.dumpbytestream(data, cw.getWriteOrder(), "ChunkWriter.write");
        }
    }

    //////////////////////////////////////////////////////////
    // Utility Methods

    protected void
    addCommonHeaders(DapRequest drq)
            throws IOException
    {
        // Add relevant headers
        ResponseFormat format = drq.getFormat();
        if(format == null)
            format = ResponseFormat.NONE;
        DapProtocol.ContentType contentheaders = DapProtocol.contenttypes.get(drq.getMode());
        String header = contentheaders.getFormat(format);
        if(header != null) {
            header = header + "; charset=utf-8";
            drq.setResponseHeader("Content-Type", header);
        } else
            DapLog.error("Cannot determine response Content-Type");

        // Not sure what this should be yet
        //setHeader("Content-Description","?");

        // Again, not sure what value to use
        //setHeader("Content-Disposition","?");

        //not legal drq.setResponseHeader("Content-Encoding", IS_BIG_ENDIAN ? BIG_ENDIAN : LITTLE_ENDIAN);
    }

    /**
     * Merge the servlet inputs into a single object
     * for easier transport as well as adding value.
     *
     * @param rq  A Servlet request object
     * @param rsp A Servlet response object
     * @return the union of the
     * servlet request and servlet response arguments
     * from the servlet engine.
     */

    protected DapRequest
    getRequestState(HttpServletRequest rq, HttpServletResponse rsp)
            throws IOException
    {
        return new DapRequest(this, rq, rsp);
    }

    //////////////////////////////////////////////////////////
    // Error Methods

    /* Note that these error returns are assumed to be before
       any DAP4 response has been generated. So they will
       set the header return code and an Error Response as body.
       Error chunks are handled elsewhere.
     */

    /**
     * Generate an error based on the parameters
     *
     * @param drq      DapRequest
     * @param httpcode 0=>no code specified
     * @param t        exception that caused the error; may be null
     * @throws IOException
     */
    protected void
    senderror(DapRequest drq, int httpcode, Throwable t)
            throws IOException
    {
        if(httpcode == 0) httpcode = HttpServletResponse.SC_BAD_REQUEST;
        ErrorResponse err = new ErrorResponse();
        err.setCode(httpcode);
        if(t == null) {
            err.setMessage("Servlet error: " + drq.getURL());
        } else {
            StringWriter sw = new StringWriter();
            PrintWriter p = new PrintWriter(sw);
            t.printStackTrace(p);
            p.close();
            sw.close();
            err.setMessage(sw.toString());
        }
        err.setContext(drq.getURL());
        String errormsg = err.buildXML();
        drq.getResponse().sendError(httpcode, errormsg);
    }

    void
    addEndianness(DapDataset dmr, DapRequest drq)
            throws DapException
    {
        DapAttribute a = dmr.findAttribute(DapUtil.LITTLEENDIANATTRNAME);
        if(a == null) {
            a = new DapAttribute(DapUtil.LITTLEENDIANATTRNAME, DapType.UINT8);
            Integer oz = (this.order == ByteOrder.BIG_ENDIAN ? 0 : 1);
            a.setValues(new Integer[]{oz});
            dmr.addAttribute(a);
        }
    }

    void
    addConstraint(DapDataset dmr, String ce)
            throws DapException
    {
        if(ce == null) return;
        DapAttribute a = dmr.findAttribute(DapUtil.CEATTRNAME);
        if(a == null) {
            a = new DapAttribute(DapUtil.CEATTRNAME, DapType.STRING);
            a.setValues(new String[]{ce});
            dmr.addAttribute(a);
        }
    }

    static public String
    printDMR(DapDataset dmr)
    {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        DMRPrinter printer = new DMRPrinter(dmr, pw).printReserved(true);
        try {
            printer.print();
            pw.close();
            sw.close();
        } catch (IOException e) {
        }
        return sw.toString();
    }

}


