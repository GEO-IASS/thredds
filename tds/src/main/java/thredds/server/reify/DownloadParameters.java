/* Copyright 2016, University Corporation for Atmospheric Research
   See the LICENSE.txt file for more information.
*/

package thredds.server.reify;


import javax.servlet.http.HttpServletRequest;
import java.io.IOException;

import static thredds.server.reify.LoadCommon.FileFormat;

/**
 * Process an HttpRequest to extract common download parameters
 */
class DownloadParameters extends Parameters
{
    //////////////////////////////////////////////////
    // Known download parameters (allow direct access)

    public FileFormat format = null;
    public String url = null;
    public String target = null;
    public String inquire = null;

    //////////////////////////////////////////////////
    // Constructor(s)

    public DownloadParameters(HttpServletRequest req)
            throws IOException
    {
        super(req);

        // File Format
        this.format = FileFormat.getformat(getparam("format"));

        // url
        this.url = getparam("url");

        // target
        this.target = getparam("target");

        // inquiry key
        this.inquire = getparam("inquire");
    }

}
