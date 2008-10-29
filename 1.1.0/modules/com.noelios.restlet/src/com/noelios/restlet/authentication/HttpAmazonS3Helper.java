/**
 * Copyright 2005-2008 Noelios Technologies.
 * 
 * The contents of this file are subject to the terms of the following open
 * source licenses: LGPL 3.0 or LGPL 2.1 or CDDL 1.0 (the "Licenses"). You can
 * select the license that you prefer but you may not use this file except in
 * compliance with one of these Licenses.
 * 
 * You can obtain a copy of the LGPL 3.0 license at
 * http://www.gnu.org/licenses/lgpl-3.0.html
 * 
 * You can obtain a copy of the LGPL 2.1 license at
 * http://www.gnu.org/licenses/lgpl-2.1.html
 * 
 * You can obtain a copy of the CDDL 1.0 license at
 * http://www.sun.com/cddl/cddl.html
 * 
 * See the Licenses for the specific language governing permissions and
 * limitations under the Licenses.
 * 
 * Alternatively, you can obtain a royaltee free commercial license with less
 * limitations, transferable or non-transferable, directly at
 * http://www.noelios.com/products/restlet-engine
 * 
 * Restlet is a registered trademark of Noelios Technologies.
 */

package com.noelios.restlet.authentication;

import java.util.Date;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.Map.Entry;

import org.restlet.data.ChallengeRequest;
import org.restlet.data.ChallengeResponse;
import org.restlet.data.ChallengeScheme;
import org.restlet.data.Form;
import org.restlet.data.Method;
import org.restlet.data.Parameter;
import org.restlet.data.Reference;
import org.restlet.data.Request;
import org.restlet.util.DateUtils;
import org.restlet.util.Series;

import com.noelios.restlet.Engine;
import com.noelios.restlet.http.HttpConstants;
import com.noelios.restlet.util.Base64;
import com.noelios.restlet.util.SecurityUtils;

/**
 * Implements the HTTP authentication for the Amazon S3 service.
 * 
 * @author Jerome Louvel
 */
public class HttpAmazonS3Helper extends AuthenticationHelper {

    /**
     * Returns the canonicalized AMZ headers.
     * 
     * @param requestHeaders
     *            The list of request headers.
     * @return The canonicalized AMZ headers.
     */
    private static String getCanonicalizedAmzHeaders(
            Series<Parameter> requestHeaders) {
        // Filter out all the AMZ headers required for AWS authentication
        final SortedMap<String, String> amzHeaders = new TreeMap<String, String>();
        String headerName;
        for (final Parameter param : requestHeaders) {
            headerName = param.getName().toLowerCase();
            if (headerName.startsWith("x-amz-")) {
                if (!amzHeaders.containsKey(headerName)) {
                    amzHeaders.put(headerName, requestHeaders
                            .getValues(headerName));
                }
            }
        }

        // Concatenate all AMZ headers
        final StringBuilder sb = new StringBuilder();
        for (final Entry<String, String> entry : amzHeaders.entrySet()) {
            sb.append(entry.getKey()).append(':').append(entry.getValue())
                    .append("\n");
        }

        return sb.toString();
    }

    /**
     * Returns the canonicalized resource name.
     * 
     * @param resourceRef
     *            The resource reference.
     * @return The canonicalized resource name.
     */
    private static String getCanonicalizedResourceName(Reference resourceRef) {
        final StringBuilder sb = new StringBuilder();
        sb.append(resourceRef.getPath());

        final Form query = resourceRef.getQueryAsForm();
        if (query.getFirst("acl", true) != null) {
            sb.append("?acl");
        } else if (query.getFirst("torrent", true) != null) {
            sb.append("?torrent");
        }

        return sb.toString();
    }

    /**
     * Constructor.
     */
    public HttpAmazonS3Helper() {
        super(ChallengeScheme.HTTP_AWS_S3, true, false);
    }

    @Override
    public String format(ChallengeRequest request) {
        return null;
    }

    @Override
    public void formatCredentials(StringBuilder sb,
            ChallengeResponse challenge, Request request,
            Series<Parameter> httpHeaders) {

        // Setup the method name
        final String methodName = request.getMethod().getName();

        // Setup the Date header
        String date = "";

        if (httpHeaders.getFirstValue("X-Amz-Date", true) == null) {
            // X-Amz-Date header didn't override the standard Date header
            date = httpHeaders.getFirstValue(HttpConstants.HEADER_DATE, true);
            if (date == null) {
                // Add a fresh Date header
                date = DateUtils.format(new Date(), DateUtils.FORMAT_RFC_1123
                        .get(0));
                httpHeaders.add(HttpConstants.HEADER_DATE, date);
            }
        }

        // Setup the ContentType header
        String contentMd5 = httpHeaders.getFirstValue(
                HttpConstants.HEADER_CONTENT_MD5, true);
        if (contentMd5 == null) {
            contentMd5 = "";
        }

        // Setup the ContentType header
        String contentType = httpHeaders.getFirstValue(
                HttpConstants.HEADER_CONTENT_TYPE, true);
        if (contentType == null) {
            boolean applyPatch = false;

            // This patch seems to apply to Sun JVM only.
            final String jvmVendor = System.getProperty("java.vm.vendor");
            if ((jvmVendor != null)
                    && (jvmVendor.toLowerCase()).startsWith("sun")) {
                final int majorVersionNumber = Engine.getJavaMajorVersion();
                final int minorVersionNumber = Engine.getJavaMinorVersion();

                if (majorVersionNumber == 1) {
                    if (minorVersionNumber < 5) {
                        applyPatch = true;
                    } else if (minorVersionNumber == 5) {
                        // Sun fixed the bug in update 10
                        applyPatch = (Engine.getJavaUpdateVersion() < 10);
                    }
                }
            }

            if (applyPatch && !request.getMethod().equals(Method.PUT)) {
                contentType = "application/x-www-form-urlencoded";
            } else {
                contentType = "";
            }
        }

        // Setup the canonicalized AmzHeaders
        final String canonicalizedAmzHeaders = getCanonicalizedAmzHeaders(httpHeaders);

        // Setup the canonicalized resource name
        final String canonicalizedResource = getCanonicalizedResourceName(request
                .getResourceRef());

        // Setup the message part
        final StringBuilder rest = new StringBuilder();
        rest.append(methodName).append('\n').append(contentMd5).append('\n')
                .append(contentType).append('\n').append(date).append('\n')
                .append(canonicalizedAmzHeaders).append(canonicalizedResource);

        // Append the AWS credentials
        sb.append(challenge.getIdentifier()).append(':').append(
                Base64.encode(SecurityUtils.toHMac(rest.toString(), new String(
                        challenge.getSecret())), false));

    }

}
