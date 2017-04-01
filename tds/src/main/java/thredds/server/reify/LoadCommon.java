/* Copyright 2012, UCAR/Unidata.
   See the LICENSE file for more information.
*/

package thredds.server.reify;

import org.apache.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import thredds.server.config.TdsContext;
import thredds.util.ContentType;
import ucar.httpservices.HTTPUtil;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.lang.reflect.Field;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.*;

public class LoadCommon
{
    //////////////////////////////////////////////////
    // Constants

    static final protected boolean DEBUG = true;

    static final protected String DEFAULTSERVLETNAME = "thredds";

    static protected int HTMLLEN = "<html>".length();

    //////////////////////////////////////////////////
    // Type Decls

    static /*package*/ enum Command
    {
        NONE, UPLOAD, DOWNLOAD, INQUIRE;

        static public Command parse(String cmd)
        {
            if(cmd == null) return null;
            for(Command x : Command.values()) {
                if(cmd.equalsIgnoreCase(x.toString())) return x;
            }
            return null;
        }
    }

    static /*package*/ enum Inquiry
    {
        DOWNLOADDIR("downloaddir"),
        UPLOADDIR("uploaddir"),
        USERNAME("username");

        private String key;

        private Inquiry(String key)
        {
            this.key = key;
        }

        public String getKey()
        {
            return this.key;
        }

        static public Inquiry parse(String key)
        {
            if(key == null) return null;
            for(Inquiry x : Inquiry.values()) {
                if(key.equalsIgnoreCase(x.toString())) return x;
            }
            return null;
        }
    }

    static /*package*/ enum FileFormat
    {
        NETCDF3("nc3"),
        NETCDF4("nc4"),;

        private String extension;

        public final String getName()
        {
            return this.toString().toLowerCase();
        }

        public final String getExtension()
        {
            return this.extension;
        }

        FileFormat(String ext)
        {
            this.extension = ext;
        }

        static public FileFormat getformat(String fmt)
        {
            if(fmt == null) return null;
            for(FileFormat rf : FileFormat.values()) {
                if(fmt.equalsIgnoreCase(rf.toString())) return rf;
            }
            return null;
        }
    }

    static public class SendError extends RuntimeException
    {
        public int httpcode = 0;
        public String msg = null;

        /**
         * Generate an error based on the parameters
         *
         * @param httpcode 0=>no code specified
         */

        public SendError(int httpcode)
        {
            this(httpcode, (String) null);
        }

        /**
         * Generate an error based on the parameters
         *
         * @param httpcode 0=>no code specified
         * @param t        exception that caused the error; may not be null
         */

        public SendError(int httpcode, Throwable t)
        {
            this(httpcode, t.getMessage());
        }

        /**
         * Generate an error based on the parameters
         *
         * @param httpcode 0=>no code specified
         * @param msg      additional info; may be null
         */
        ;

        public SendError(int httpcode, String msg)
        {
            if(httpcode == 0) httpcode = HttpServletResponse.SC_BAD_REQUEST;
            if(msg == null)
                msg = "";
            this.httpcode = httpcode;
            this.msg = msg;
        }


    }

    //////////////////////////////////////////////////
    // Static variables

    static org.slf4j.Logger logServerStartup;
    static org.slf4j.Logger log;

    static {
        logServerStartup = org.slf4j.LoggerFactory.getLogger("serverStartup");
        log = org.slf4j.LoggerFactory.getLogger(LoadCommon.class);
    }

    //////////////////////////////////////////////////
    // Static Methods

    static File
    findSystemTempDir(String[] candidates)
    {
        for(String candidate : candidates) {
            File f = new File(candidate);
            if(f.exists() && f.canRead() && f.canWrite())
                return f;
            if(f.mkdirs()) // Try to create the path
                return f;
        }
        // As a last resort use the java temp file mechanism
        try {
            File tempfile = File.createTempFile("tmp", "tmp");
            File tempdir = tempfile.getParentFile();
            if(!tempdir.canWrite() || !tempdir.canRead())
                return null;
            return tempdir;
        } catch (IOException e) {
            return null;
        }
    }

    static public String
    mapToString(Map<String, String> map, boolean encode, String... order)
    {
        List<String> orderlist;
        if(order == null)
            orderlist = new ArrayList<String>(map.keySet());
        else
            orderlist = Arrays.asList(order);
        StringBuilder b = new StringBuilder();
        // Make two passes: one from order, and one from remainder
        boolean first = true;
        for(int i = 0; i < orderlist.size(); i++) {
            String key = orderlist.get(i);
            String value = map.get(key);
            if(value == null) continue; // ignore
            if(!first) b.append("&");
            b.append(key);
            b.append("=");
            b.append(encode ? urlEncode(value) : value);
            first = false;
        }
        for(Map.Entry<String, String> entry : map.entrySet()) {
            if(orderlist.contains(entry.getKey())) continue;
            if(!first) b.append("&");
            b.append(entry.getKey());
            b.append("=");
            b.append(encode ? urlEncode(entry.getValue()) : entry.getValue());
            first = false;
        }
        return b.toString();
    }

    static public Map<String, String>
    parseMap(String params, char sep, boolean decode)
    {
        Map<String, String> map = new HashMap<>();
        if(params == null || params.length() == 0)
            return map;
        String[] pieces = params.split("[&]");
        for(int i = 0; i < pieces.length; i++) {
            String piece = pieces[i].trim();
            String[] pair = piece.split("[=]");
            String key = pair[0].trim();
            if(pair.length >= 2) {
                String v = pair[1].trim();
                if(decode) v = urlDecode(v);
                map.put(key, v);
            } else if(pair.length == 1) {
;                map.put(key, "");
            } else
                assert false : "split() failed";
        }
        return map;
    }

    static public List<String>
    parseList(String params, char sep, boolean decode)
    {
        List<String> list = new ArrayList<>();
        if(params == null || params.length() == 0)
            return list;
        String regex = "[ ]*[" + sep + "][ ]*";
        String[] pieces = params.split(regex);
        for(int i = 0; i < pieces.length; i++) {
            String piece = pieces[i];
            if(decode) piece = urlDecode(piece);
            list.add(piece);
        }
        return list;
    }

    static public String
    urlEncode(String s)
    {
        try {
            s = URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            assert false : e.getMessage();
        }
        return s;
    }

    static public String
    urlDecode(String s)
    {
        try {
            s = URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            assert false : e.getMessage();
        }
        return s;
    }

    static public String
    getStackTrace(Exception e)
    {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        pw.close();
        try {
            sw.close();
        } catch (IOException ioe) {
            return "close failure";
        }
        return sw.toString();
    }

    //////////////////////////////////////////////////
    // Instance variables

    @Autowired
    protected TdsContext tdsContext = null;

    protected boolean initialized = false;
    protected boolean once = false;

    protected HttpServletRequest req = null;
    protected HttpServletResponse res = null;

    protected String server = null;
    protected String requestname = null;
    protected String threddsname = null;

    protected String uploaddir = null;
    protected String downloaddir = null;

    //////////////////////////////////////////////////
    // Constructor(s)

    public LoadCommon()
            throws ServletException
    {
        // Do not know how to get spring to invoke init when mocking.
        if(!initialized)
            init();
    }

    //////////////////////////////////////////////////
    // Servlet API (Selected)

    public void init()
            throws ServletException
    {
        try {
            if(initialized)
                return;
            initialized = true;
            logServerStartup.info(getClass().getName() + " initialization");
            System.setProperty("file.encoding", "UTF-8");
            Field charset = Charset.class.getDeclaredField("defaultCharset");
            charset.setAccessible(true);
            charset.set(null, null);
        } catch (Exception e) {
            throw new ServletException(e);
        }
    }

    //////////////////////////////////////////////////

    /**
     * Invoked on first get so that everything is available,
     * especially Spring stuff.
     */
    public void initOnce(HttpServletRequest req)
            throws SendError
    {
        if(once)
            return;
        once = true;
        log.info(getClass().getName() + " GET initialization");
        if(this.tdsContext == null)
            throw new SendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Cannot find TDS Context");
        // Get server host + port name
        StringBuilder buf = new StringBuilder();
        buf.append(req.getServerName());
        int port = req.getServerPort();
        if(port > 0) {
            buf.append(":");
            buf.append(port);
        }
        this.server = buf.toString();

        // Obtain servlet path info
        String tmp = HTTPUtil.canonicalpath(req.getContextPath());
        this.threddsname = HTTPUtil.nullify(HTTPUtil.relpath(tmp));
        tmp = HTTPUtil.canonicalpath(req.getServletPath());
        this.requestname = HTTPUtil.nullify(HTTPUtil.relpath(tmp));

        if(this.threddsname == null)
            this.threddsname = DEFAULTSERVLETNAME;

        // Get the upload dir
        File updir = tdsContext.getUploadDir();
        if(updir == null) {
            log.warn("No tds.upload.dir specified");
            this.uploaddir = null;
        } else
            this.uploaddir = HTTPUtil.canonicalpath(updir.getAbsolutePath());
        // Get the download dir
        File downdir = tdsContext.getDownloadDir();
        if(downdir == null) {
            log.warn("No tds.download.dir specified");
            this.downloaddir = null;
        } else
            this.downloaddir = HTTPUtil.canonicalpath(downdir.getAbsolutePath());

    }

    //////////////////////////////////////////////////

    protected String
    inquire()
    {
        Map<String, String> result = new HashMap<>();
        // Return all known server key values
        if(this.downloaddir != null)
            result.put("downloaddir", this.downloaddir);
        if(this.uploaddir != null)
            result.put("uploaddir", this.uploaddir);
        String sresult = mapToString(result, true, "download");
        return sresult;
    }

    protected void
    sendOK(String msg)
    {
        sendReply(HttpStatus.SC_OK, msg);
    }

    protected void
    sendError(int code, String msg)
    {
        System.err.printf("Error code=%d%b%s%n",code,msg);
        System.err.flush();
        sendReply(code, msg);
    }


    protected void
    sendReply(int code, String msg)
    {
        try {
            res.setStatus(code);
            String prefix = msg.trim();
            boolean usetext;
            if(prefix.length() < HTMLLEN)
                usetext = true;
            else {
                prefix = prefix.substring(0, HTMLLEN);
                usetext = !("<html>".equalsIgnoreCase(prefix));
            }
            res.setContentType(
                    usetext ? ContentType.text.getContentHeader()
                            : ContentType.html.getContentHeader()
            );
            try {
                PrintWriter pw = res.getWriter();
                pw.print(msg);
                pw.close();
                pw.flush();
                res.flushBuffer();
            } catch (IOException ioe) {
                log.error(ioe.getMessage());
            }
        } catch (Exception e) {
            res.setStatus(HttpStatus.SC_INTERNAL_SERVER_ERROR);
        }
    }


}
