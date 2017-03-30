/* Copyright 2016, University Corporation for Atmospheric Research
   See the LICENSE.txt file for more information.
*/

package thredds.server.reify;

import org.junit.Assert;
import org.springframework.validation.Errors;
import ucar.httpservices.HTTPFactory;
import ucar.httpservices.HTTPMethod;
import ucar.httpservices.HTTPUtil;
import ucar.unidata.util.test.UnitTestCommon;

import java.io.File;
import java.io.IOException;
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

    protected List<AbstractTestCase> alltestcases = new ArrayList<>();

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
            try (HTTPMethod method = HTTPFactory.Get(b.toString())) {
                code = callserver(method);
                byte[] bytes = method.getResponseAsBytes();
                if(code != 200) {
                    stderr.println("Server call failed: status=" + code);
                    return null;
                }
                // Convert to string
                sresult = "";
                if(bytes != null && bytes.length > 0)
                    sresult = new String(bytes, "utf8");
                sresult = LoadUtils.urlDecode(sresult);
                stderr.printf("Getproperties: result=|%s|", sresult);
            }
        } catch (IOException e) {
            System.err.println("Server call failure: " + e.getMessage());
            return null;
        }
        Map<String, String> result = LoadUtils.parseMap(sresult, ';', true);
        return result;
    }

    public int
    callserver(HTTPMethod method)
            throws IOException
    {
        int code = 0;
        // Make method call
        method.execute();
        code = method.getStatusCode();
        org.apache.http.Header h = method.getResponseHeader(STATUSCODEHEADER);
        if(h != null) {
            String scode = h.getValue();
            try {
                int tmpcode = Integer.parseInt(scode);
                if(tmpcode > 0)
                    code = tmpcode;
            } catch (NumberFormatException e) {
                code = code; // ignore
            }
        }
        return code;
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

    static File
    makedir(String name, boolean clear)
            throws IOException
    {
        File dir = new File(name);
        dir.mkdirs(); // ensure existence
        // Change permissions to allow read/write by anyone
        dir.setExecutable(true, false);
        dir.setReadable(true, false);
        dir.setWritable(true, false);
        if(!dir.canRead())
            throw new IOException(name + ": cannot read");
        if(!dir.canWrite())
            throw new IOException(name + ": cannot write");
        // optionally clear out the dir
        if(clear)
            deleteTree(name, false);
        return dir;
    }

}
