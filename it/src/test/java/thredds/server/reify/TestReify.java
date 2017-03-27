/* Copyright 2016, University Corporation for Atmospheric Research
   See the LICENSE.txt file for more information.
*/

package thredds.server.reify;

import org.apache.http.HttpStatus;
import org.junit.Assert;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.validation.Errors;
import ucar.httpservices.HTTPFactory;
import ucar.httpservices.HTTPMethod;
import ucar.httpservices.HTTPUtil;
import ucar.unidata.util.test.UnitTestCommon;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

abstract public class TestReify extends UnitTestCommon
{
    static protected final boolean DEBUG = false;

    //////////////////////////////////////////////////
    // Constants

    static protected final String THREDDSPREFIX = "/thredds";
    static protected final String DOWNPREFIX = "/download";
    static protected final String UPPREFIX = "/upload";
    static protected String DOWNLOADDIR;
    static protected String UPLOADDIR;

    static final protected String STATUSCODEHEADER = "x-download-status";

    static {
        // Try to locate a temporary directory
        File tmp = new File("C:/Temp");
        if(!tmp.exists() || !tmp.isDirectory() || !tmp.canRead() || !tmp.canWrite())
            tmp = null;
        if(tmp != null) {
            tmp = new File("/tmp");
            if(!tmp.exists() || !tmp.isDirectory() || !tmp.canRead() || !tmp.canWrite())
                tmp = null;
        }
        if(tmp == null)
            tmp = new File(System.getProperty("user.dir"));
        File dload = new File(tmp, "download");
        dload.mkdirs();
        DOWNLOADDIR = HTTPUtil.canonicalpath(dload.getAbsolutePath());
        File uload = new File(tmp, "upload");
        uload.mkdirs();
        UPLOADDIR = HTTPUtil.canonicalpath(uload.getAbsolutePath());
    }

    //////////////////////////////////////////////////
    // Type Decls

    static public class NullValidator implements org.springframework.validation.Validator
    {
        public boolean supports(Class<?> clazz)
        {
            return true;
        }

        public void validate(Object target, Errors errors)
        {
            return;
        }
    }

    static abstract class AbstractTestCase
    {
        public String downloadroot = DOWNLOADDIR;
        public String uploadroot = UPLOADDIR;

        //////////////////////////////////////////////////

        protected String url;
        protected String target;

        AbstractTestCase(String url)
        {
            this.url = url;
        }

        //////////////////////////////////////////////////
        // Subclass defined

        abstract public String toString();

        //////////////////////////////////////////////////
        // Accessors

        public String getURL()
        {
            return this.url;
        }

    }

    //////////////////////////////////////////////////
    // Instance variables

    protected MockMvc mockMvc = null;

    protected List<AbstractTestCase> alltestcases = new ArrayList<>();

    protected int lastcode = HttpStatus.SC_OK;

    //////////////////////////////////////////////////

    abstract void defineAllTestCases();

    abstract void doOneTest(AbstractTestCase tc) throws Exception;

    //////////////////////////////////////////////////    

    public void
    doAllTests()
            throws Exception
    {
        Assert.assertTrue("No defined testcases", this.alltestcases.size() > 0);
        for(int i = 0; i < this.alltestcases.size(); i++) {
            doOneTest(this.alltestcases.get(i));
        }
    }

    //////////////////////////////////////////////////
    // Utilities

    public Map<String, String>
    getServerProperties(String server)
    {
        StringBuilder b = new StringBuilder();
        b.append(server);
        b.append("/");
        b.append("?request=inquire&inquire=downloaddir;username");
        int code = 0;
        String sresult = null;
        try {
            sresult = callserver(b.toString());
            code = getStatus();
        } catch (IOException e) {
            System.err.println("Server call failure: " + e.getMessage());
            return null;
        }
        if(code != 200) {
            System.err.println("Server call failed: status=" + code);
            return null;
        }
        Map<String, String> result = ReifyUtils.parseMap(sresult, ';', true);

        return result;
    }

    protected MvcResult
    perform(String surl, MockMvc mockMvc, byte[] postdata)
            throws Exception
    {
        URL url = new URL(surl);
        String path = url.getPath();
        MockHttpServletRequestBuilder rb;
        if(postdata != null)
            rb = MockMvcRequestBuilders.post(path).servletPath(path).content(postdata);
        else
            rb = MockMvcRequestBuilders.get(path).servletPath(path);
        //if(query != null) rb.param(CONSTRAINTTAG, query);
        MvcResult result = mockMvc.perform(rb).andReturn();
        MockHttpServletResponse msrp = result.getResponse();
        this.lastcode = msrp.getStatus();
        return result;
    }

    public int
    getStatus()
    {
        return this.lastcode;
    }

    public String
    callserver(String url)
            throws IOException
    {
        // Make method call
        byte[] bytes = null;
        this.lastcode = 0;
        try (HTTPMethod method = HTTPFactory.Get(url)) {
            method.execute();
            this.lastcode = method.getStatusCode();
            org.apache.http.Header h = method.getResponseHeader(STATUSCODEHEADER);
            if(h != null) {
                String scode = h.getValue();
                int code;
                try {
                    code = Integer.parseInt(scode);
                    if(code > 0)
                        this.lastcode = code;
                } catch (NumberFormatException e) {
                    this.lastcode = 0;
                }
            }
            bytes = method.getResponseAsBytes();
        }
        // Convert to string
        String sbytes = "";
        if(bytes != null && bytes.length > 0)
            sbytes = new String(bytes, "utf8");
        if(this.lastcode != 200)
            return sbytes;
        String result = ReifyUtils.urlDecode(sbytes);
        return result;
    }

    static public String
    replyCompare(Map<String, String> result, Map<String, String> base)
    {
        StringBuilder b = new StringBuilder();
        // do two ways to catch added plus new
        for(Map.Entry<String, String> entry : result.entrySet()) {
            String basevalue = base.get(entry.getKey());
            if(basevalue == null) {
                b.append(String.format("Added: %s%n",
                        entry.getKey()));
            } else {
                String rvalue = entry.getValue();
                if(!rvalue.equals(basevalue)) {
                    b.append(String.format("Change: %s: %s to %s%n",
                            entry.getKey(), basevalue, rvalue));
                }
            }
        }
        for(Map.Entry<String, String> entry : base.entrySet()) {
            String rvalue = result.get(entry.getKey());
            if(rvalue == null) {
                b.append(String.format("Deleted: %s%n",
                        entry.getKey()));
            }
        }
        return (b.toString().length() > 0 ? b.toString() : null);
    }

    /**
     * @param root       delete all files under this root
     * @param deleteroot true => delete root also
     * @return true if delete suceeded
     */
    static public boolean
    deleteTree(String root, boolean deleteroot)
    {
        if(root == null || root.length() == 0)
            return false;
        File rootfile = new File(root);
        if(!rootfile.exists()) return false;
        if(!deleteTree(rootfile)) return false;
        if(deleteroot && !rootfile.delete()) return false;
        return true;
    }

    static protected boolean
    deleteTree(File root)
    {
        File[] contents = root.listFiles();
        for(File f : contents) {
            if(f.isDirectory() && !deleteTree(f)) return false;
            if(!f.delete()) return false;
        }
        return true;
    }

}
