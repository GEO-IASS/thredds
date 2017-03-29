/* Copyright 2016, University Corporation for Atmospheric Research
   See the LICENSE.txt file for more information.
*/

package thredds.server.reify;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import thredds.server.config.TdsContext;
import ucar.httpservices.HTTPFactory;
import ucar.httpservices.HTTPFormBuilder;
import ucar.httpservices.HTTPMethod;
import ucar.httpservices.HTTPUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.UserPrincipal;

public class TestUpload extends TestReify
{

    static protected final boolean DEBUG = false;

    //////////////////////////////////////////////////
    // Constants

    static protected final String DEFAULTSERVER = "localhost:8081";
    static protected final String DEFAULTUPURL = "http://" + DEFAULTSERVER + THREDDSPREFIX + UPPREFIX;

    //////////////////////////////////////////////////
    // Type Decls

    //////////////////////////////////////////////////

    static class TestCase extends AbstractTestCase
    {
        static public String server = DEFAULTSERVER;
        static String svcdir = null;

        static public void setServerDir(String dir)
        {
            svcdir = dir;
        }

        //////////////////////////////////////////////////

        // The form fields
        public String status;
        public byte[] file;
        public boolean overwrite;
        public String target;

        public String filename;

        //////////////////////////////////////////////////

        protected TestCase(String filename, boolean overwrite, String target)
        {
            super(null);
            this.filename = HTTPUtil.canonicalpath(filename);
            this.status = "";
            this.overwrite = overwrite;
            this.target = HTTPUtil.canonicalpath(HTTPUtil.nullify(target));
            try {
                this.file = HTTPUtil.readbinaryfile(new File(filename));
            } catch (IOException ioe) {
                throw new IllegalArgumentException(ioe);
            }
        }

        @Override
        public String toString()
        {
            StringBuilder buf = new StringBuilder();
            buf.append("{");
            buf.append("file=");
            buf.append(this.filename);
            buf.append(",");
            buf.append("overwrite=");
            buf.append(this.overwrite);
            buf.append(",");
            buf.append("target=");
            buf.append(this.target);
            buf.append("}");
            return buf.toString();
        }

        public String
        makeURL()
        {
            StringBuilder b = new StringBuilder();
            b.append("http://");
            b.append("dmh:aseymayo@");
            b.append(server);
            b.append(THREDDSPREFIX);
            b.append(UPPREFIX);
            return b.toString();
        }
    }

    //////////////////////////////////////////////////
    // Instance variables
    protected String uploaddir = null;

    //////////////////////////////////////////////////
    // Junit test methods

    @Before
    public void setup()
            throws Exception
    {
        HTTPMethod.TESTING = true;
        this.uploaddir = System.getProperty("tds.upload.dir");
        Assert.assertTrue("tds.upload.dir missinmg", this.uploaddir != null);
        File dir = new File(this.uploaddir);
        // Change permissions to allow read/write by anyone
        dir.setExecutable(true, false);
        dir.setReadable(true, false);
        dir.setWritable(true, false);
        // clear out the upload dir
        deleteTree(this.uploaddir, false);
        defineAllTestCases();
        prop_visual = true;
    }

    @Test
    public void
    testUpload()
            throws Exception
    {
        super.doAllTests();
    }

    //////////////////////////////////////////////////
    // Primary test method

    @Override
    public void doOneTest(AbstractTestCase tc)
            throws Exception
    {
        TestCase test = (TestCase) tc;
        System.out.println("Testcase: " + test.toString());

        org.apache.http.HttpEntity entity = buildPostContent(test);
        String url = test.makeURL();
        String sresult = null;
        try (HTTPMethod m = HTTPFactory.Post(url)) {
            m.setRequestContent(entity);
            int code = callserver(m);
            switch (code) {
            case 200:
                break;
            case 401:
            case 403:
                Assert.assertTrue(String.format("Access failure: %d", code), code == 200);
                break;
            default:
                Assert.assertTrue(String.format("httpcode=%d", code), code == 200);
                break;
            }
            // Collect the output
            byte[] byteresult = m.getResponseAsBytes();
            sresult = new String(byteresult, UTF8);
        }
        if(prop_visual) {
            visual("TestUpload:", sresult);
        }

        if(prop_diff) {
            // Verify that the file exists
            String targetpath = test.target;
            File src = new File(test.filename);
            if(targetpath == null) {
                // extract the basename
                targetpath = src.getName();
            }
            StringBuilder buf = new StringBuilder();
            buf.append(this.uploaddir);
            buf.append("/");
            buf.append(targetpath);
            String abstarget = HTTPUtil.canonicalpath(buf.toString());
            File targetfile = new File(abstarget);
            Assert.assertTrue("***Fail: Upload file not created: "+abstarget, targetfile.exists());
            Assert.assertTrue("***Fail: Upload file not readable: "+abstarget, targetfile.canRead());
            // Do a byte for byte comparison
            byte[] srcbytes = readbinaryfile(src.getAbsolutePath());
            byte[] targetbytes = readbinaryfile(targetfile.getAbsolutePath());
            Assert.assertTrue(
                    String.format("***Fail: Upload file (%d bytes) and Original file (%d bytes) differ in size",
                            targetbytes.length, srcbytes.length),
                    targetbytes.length == srcbytes.length);
            for(int i=0;i<srcbytes.length;i++) {
                if(srcbytes[i] != targetbytes[i])
                    Assert.fail("***Fail: Upload file and Source file differ at byte " + i);
            }
            System.err.println("***Pass: Upload file exists and Source and uploaded files are identical");
        }
    }

    //////////////////////////////////////////////////
    // Test cases

    protected void
    defineAllTestCases()
    {
        alltestcases.add(
                new TestCase(/*file=*/"d:/t.nc", true,/*target=*/"")
        );
    }

    protected org.apache.http.HttpEntity
    buildPostContent(TestCase tc)
            throws IOException
    {
        HTTPFormBuilder builder = new HTTPFormBuilder();
        builder.add("status", tc.status);
        builder.add("file", tc.file, tc.filename);
        if(tc.overwrite)
            builder.add("overwrite", "true");
        if(tc.target != null)
            builder.add("target", tc.target);
        return builder.build();
    }
}
