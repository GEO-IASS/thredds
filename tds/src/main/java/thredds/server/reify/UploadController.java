/* Copyright 2016, University Corporation for Atmospheric Research
   See the LICENSE.txt file for more information.
*/

package thredds.server.reify;

import org.apache.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import ucar.httpservices.HTTPUtil;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;

/**
 * File upload to a specific directory.
 * As a rule, this needs to coordinate with
 * the upload catalog entry specified
 * in the catalog (see the documentation for upload/download).
 * The tag for this controller is .../upload/*
 * <p>
 * The goal for this controller is to allow external code to have the
 * thredds server upload a file and store it in a specific place.
 * <p>
 * Assumptions:
 * 1. The external code has authenticated to the thredds server
 * using some authentication mechanism such as
 * client-side certificates or tomcat roles.
 */

@Controller
@RequestMapping(value = {"/upload", "/restrictedAccess/upload"})
public class UploadController extends LoadCommon
{
    //////////////////////////////////////////////////
    // Constants

    static final protected boolean DEBUG = true;
    static final protected String DEFAULTUPLOADFORM = "WEB-INF/upload.html";

    //////////////////////////////////////////////////
    // Instance variables

    protected String uploaddirname = null;
    protected String uploadform = null;
    protected Parameters params = null;
    protected boolean issetup = false;

    //////////////////////////////////////////////////
    // Constructor(s)

    public UploadController()
            throws ServletException
    {
        super();
    }

    /**
     * Invoked on first get request so that everything is available,
     * especially Spring stuff.
     */

    /**
     * Invoked once on first request so that everything is available,
     * especially Spring stuff.
     */
    public void
    doonce(HttpServletRequest req)
            throws SendError

    {
        if(once)
            return;
        super.initOnce(req);

        if(this.uploaddir == null)
            throw new SendError(HttpStatus.SC_PRECONDITION_FAILED, "Upload disabled");

        this.uploaddirname = new File(this.uploaddir).getName();

        // Get the upload form
        File upform = null;
        upform = tdsContext.getUploadForm();
        if(upform == null) {   // Look in WEB-INF directory
            File root = tdsContext.getServletRootDirectory();
            upform = new File(root, DEFAULTUPLOADFORM);
        }
        if(upform == null)
            throw new SendError(HttpServletResponse.SC_PRECONDITION_FAILED, "No tds.upload.dir specified");
        if(!upform.exists())
            throw new SendError(HttpServletResponse.SC_PRECONDITION_FAILED,
                    "Upload html form does not exist: " + upform.getName());
        if(!upform.canRead())
            throw new SendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Upload html form not readable: " + upform.getName());
        try {
            FileInputStream fis = new FileInputStream(upform);
            this.uploadform = HTTPUtil.readtextfile(fis);
            fis.close();
        } catch (IOException e) {
            logServerStartup.warn("Cannot read upload html form file: " + upform.getName());
        }
    }

    // Setup for each request
    public void setup(HttpServletRequest req, HttpServletResponse resp)
            throws SendError
    {

        this.req = req;
        this.res = resp;
        if(!once)
            doonce(req);

        // Parse any query parameters
        try {
            this.params = new Parameters(req);
        } catch (IOException ioe) {
            throw new SendError(res.SC_BAD_REQUEST, ioe);
        }
    }

    //////////////////////////////////////////////////
    // Controller entry point(s)

    @RequestMapping(value = "**", method = RequestMethod.GET)
    public void
    doGet(HttpServletRequest req, HttpServletResponse res)
            throws ServletException
    {

        try {
            setup(req, res);

            switch (this.params.command) {
            case NONE:
            case UPLOAD:
                // Send back the upload form
                sendForm("No files uploaded");
                break;
            case INQUIRE:
                String result = inquire();
                // Send back the inquiry answers
                sendOK(result);
                break;
            default:
                throw new SendError(res.SC_BAD_REQUEST, "Unknown command: " + this.params.command);
            }
        } catch (SendError se) {
            sendError(se.httpcode, se.msg);
        } catch (Exception e) {
            String msg = getStackTrace(e);
            sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, msg);
        }
    }

    @RequestMapping(value = "**", method = RequestMethod.POST)
    public void
    doPost(HttpServletRequest req, HttpServletResponse res)
            throws ServletException
    {
        try {
            setup(req, res);

            Collection<Part> parts = null;
            parts = req.getParts();
            String target = null;
            String filename = null;
            boolean overwrite = false;
            byte[] contents = null;
            for(Part part : parts) {
                String field = part.getName();
                InputStream stream = null;
                if(field.equalsIgnoreCase("file")) {
                    filename = HTTPUtil.nullify(part.getSubmittedFileName());
                    stream = part.getInputStream();
                    contents = HTTPUtil.readbinaryfile(stream);
                } else if(field.equalsIgnoreCase("overwrite")) {
                    stream = part.getInputStream();
                    String value = HTTPUtil.nullify(HTTPUtil.readtextfile(stream));
                    overwrite = (value != null && value.equalsIgnoreCase("true"));
                } else if(field.equalsIgnoreCase("target")) {
                    stream = part.getInputStream();
                    String value = HTTPUtil.nullify(HTTPUtil.readtextfile(stream));
                    target = value;
                }  // else ignore
            }

            if(target == null) {
                // extract the basename
                File t = new File(filename);
                target = t.getName();
            }
            StringBuilder buf = new StringBuilder();
            buf.append(HTTPUtil.canonicalpath(this.uploaddir));
            buf.append("/");
            buf.append(target);
            String abstarget = HTTPUtil.canonicalpath(buf.toString());
            File targetfile = new File(abstarget);
            File targetdir = targetfile.getParentFile();
            if(!targetdir.exists() && !targetdir.mkdirs())
                sendError(HttpStatus.SC_FORBIDDEN, "Cannot create target parent directory: " + target);
            if(targetfile.exists() && !overwrite)
                sendError(HttpStatus.SC_FORBIDDEN, "Target exists and replace was not specified: " + target);
            if(targetfile.exists() && !targetfile.canWrite())
                sendError(HttpStatus.SC_FORBIDDEN, "Target exists and is read-only: " + target);
            HTTPUtil.writebinaryfile(contents, targetfile);
            String msg = String.format("File upload succeeded: %s -> %s",
                    filename, target);
            sendOK(msg);
        } catch (IOException ioe) {
            sendError(HttpStatus.SC_NOT_FOUND, ioe.getMessage());
        }

    }

    //////////////////////////////////////////////////

    protected void
    sendForm(String msg)
    {
        String reply = buildForm(msg);
        sendOK(reply);
    }

    protected void
    sendErrorForm(int code, String err)
    {
        String msg = String.format("Error: %d; %s", code, err);
        String reply = buildForm(msg);
        sendError(code, reply);
    }

    protected String
    buildForm(String msg)
    {
        StringBuilder svc = new StringBuilder();
        svc.append(this.server);
        svc.append("/");
        svc.append(this.threddsname);
        String form = String.format(this.uploadform,
                svc.toString(),
                msg,
                this.server,
                this.uploaddirname
        );
        return form;
    }

    @Override
    protected void
    sendReply(int code, String msg)
    {
        if(DEBUG) {
            System.err.printf("XXX: code=%d msg=%n%s%n", code, msg);
        }
        super.sendReply(code,msg);
    }

}

@ControllerAdvice
class ExceptionControllerAdvice
{
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public String exception(HttpServletRequest rq, Exception e)
    {
        UploadController.log.error("HttpRequestMethodNotSupportedException");
        return "HttpRequestMethodNotSupportedException";
    }
}
