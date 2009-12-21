/**
 * Copyright 2005-2009 Noelios Technologies.
 * 
 * The contents of this file are subject to the terms of one of the following
 * open source licenses: LGPL 3.0 or LGPL 2.1 or CDDL 1.0 or EPL 1.0 (the
 * "Licenses"). You can select the license that you prefer but you may not use
 * this file except in compliance with one of these Licenses.
 * 
 * You can obtain a copy of the LGPL 3.0 license at
 * http://www.opensource.org/licenses/lgpl-3.0.html
 * 
 * You can obtain a copy of the LGPL 2.1 license at
 * http://www.opensource.org/licenses/lgpl-2.1.php
 * 
 * You can obtain a copy of the CDDL 1.0 license at
 * http://www.opensource.org/licenses/cddl1.php
 * 
 * You can obtain a copy of the EPL 1.0 license at
 * http://www.opensource.org/licenses/eclipse-1.0.php
 * 
 * See the Licenses for the specific language governing permissions and
 * limitations under the Licenses.
 * 
 * Alternatively, you can obtain a royalty free commercial license with less
 * limitations, transferable or non-transferable, directly at
 * http://www.noelios.com/products/restlet-engine
 * 
 * Restlet is a registered trademark of Noelios Technologies.
 */

package org.restlet.engine.http.connector;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.security.Principal;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;

import org.restlet.Context;
import org.restlet.Response;
import org.restlet.Server;
import org.restlet.data.Digest;
import org.restlet.data.Encoding;
import org.restlet.data.Form;
import org.restlet.data.Language;
import org.restlet.data.Method;
import org.restlet.data.Parameter;
import org.restlet.data.Protocol;
import org.restlet.data.Status;
import org.restlet.engine.ConnectorHelper;
import org.restlet.engine.http.header.ContentType;
import org.restlet.engine.http.header.HeaderConstants;
import org.restlet.engine.http.header.HeaderReader;
import org.restlet.engine.http.header.HeaderUtils;
import org.restlet.engine.http.header.RangeUtils;
import org.restlet.engine.http.io.ChunkedInputStream;
import org.restlet.engine.http.io.ChunkedOutputStream;
import org.restlet.engine.http.io.InputEntityStream;
import org.restlet.engine.util.Base64;
import org.restlet.representation.EmptyRepresentation;
import org.restlet.representation.InputRepresentation;
import org.restlet.representation.ReadableRepresentation;
import org.restlet.representation.Representation;
import org.restlet.service.ConnectorService;
import org.restlet.util.Series;

/**
 * Generic HTTP server connection.
 * 
 * @author Jerome Louvel
 */
public abstract class ServerConnection extends Connection<Server> {

    /**
     * Constructor.
     * 
     * @param helper
     *            The parent connector helper.
     * @param socket
     *            The underlying socket.
     * @throws IOException
     */
    public ServerConnection(BaseHelper<Server> helper, Socket socket)
            throws IOException {
        super(helper, socket);
    }

    /**
     * Adds the response headers.
     * 
     * @param response
     *            The response to inspect.
     * @param headers
     *            The headers series to update.
     */
    protected void addResponseHeaders(Response response,
            Series<Parameter> headers) {
        HeaderUtils.addResponseHeaders(response, headers);
    }

    /**
     * Asks the server connector to immediately commit the given response
     * associated to this request, making it ready to be sent back to the
     * client. Note that all server connectors don't necessarily support this
     * feature.
     * 
     * @param response
     *            The response to commit.
     */
    public void commit(Response response) {
        getHelper().getPendingResponses().add(response);
    }

    /**
     * Returns the inbound message entity if available.
     * 
     * @param headers
     *            The headers to use.
     * @return The inbound message if available.
     */
    public Representation createInboundEntity(Series<Parameter> headers) {
        Representation result = null;
        long contentLength = HeaderUtils.getContentLength(headers);
        boolean chunkedEncoding = HeaderUtils.isChunkedEncoding(headers);

        // Create the representation
        if ((contentLength != Representation.UNKNOWN_SIZE) || chunkedEncoding) {
            InputStream inboundEntityStream = getInboundEntityStream(
                    contentLength, chunkedEncoding);
            ReadableByteChannel inboundEntityChannel = getInboundEntityChannel(
                    contentLength, chunkedEncoding);

            if (inboundEntityStream != null) {
                result = new InputRepresentation(inboundEntityStream, null,
                        contentLength) {
                    @Override
                    public void release() {
                        super.release();
                        setInboundBusy(false);
                    }
                };
            } else if (inboundEntityChannel != null) {
                result = new ReadableRepresentation(inboundEntityChannel, null,
                        contentLength) {
                    @Override
                    public void release() {
                        super.release();
                        setInboundBusy(false);
                    }
                };
            }

            result.setSize(contentLength);
        } else {
            result = new EmptyRepresentation();

            // Mark the inbound as free so new messages can be read if possible
            setInboundBusy(false);
        }

        if (headers != null) {
            // Extract some interesting header values
            for (Parameter header : headers) {
                if (header.getName().equalsIgnoreCase(
                        HeaderConstants.HEADER_CONTENT_ENCODING)) {
                    HeaderReader hr = new HeaderReader(header.getValue());
                    String value = hr.readValue();

                    while (value != null) {
                        Encoding encoding = Encoding.valueOf(value);

                        if (!encoding.equals(Encoding.IDENTITY)) {
                            result.getEncodings().add(encoding);
                        }
                        value = hr.readValue();
                    }
                } else if (header.getName().equalsIgnoreCase(
                        HeaderConstants.HEADER_CONTENT_LANGUAGE)) {
                    HeaderReader hr = new HeaderReader(header.getValue());
                    String value = hr.readValue();

                    while (value != null) {
                        result.getLanguages().add(Language.valueOf(value));
                        value = hr.readValue();
                    }
                } else if (header.getName().equalsIgnoreCase(
                        HeaderConstants.HEADER_CONTENT_TYPE)) {
                    ContentType contentType = new ContentType(header.getValue());
                    result.setMediaType(contentType.getMediaType());
                    result.setCharacterSet(contentType.getCharacterSet());
                } else if (header.getName().equalsIgnoreCase(
                        HeaderConstants.HEADER_CONTENT_RANGE)) {
                    RangeUtils.parseContentRange(header.getValue(), result);
                } else if (header.getName().equalsIgnoreCase(
                        HeaderConstants.HEADER_CONTENT_MD5)) {
                    result.setDigest(new Digest(Digest.ALGORITHM_MD5, Base64
                            .decode(header.getValue())));
                }
            }
        }

        return result;
    }

    /**
     * Creates a new request.
     * 
     * @param context
     *            The current context.
     * @param connection
     *            The associated connection.
     * @param methodName
     *            The method name.
     * @param resourceUri
     *            The target resource URI.
     * @param version
     *            The protocol version.
     * @param headers
     *            The request headers.
     * @param entity
     *            The request entity.
     * @param confidential
     *            True if received confidentially.
     * @param userPrincipal
     *            The user principal.
     * @return The created request.
     */
    protected abstract ConnectedRequest createRequest(Context context,
            ServerConnection connection, String methodName, String resourceUri,
            String version, Series<Parameter> headers, Representation entity,
            boolean confidential, Principal userPrincipal);

    /**
     * Returns the inbound message entity channel if it exists.
     * 
     * @param size
     *            The expected entity size or -1 if unknown.
     * 
     * @return The inbound message entity channel if it exists.
     */
    public ReadableByteChannel getInboundEntityChannel(long size,
            boolean chunked) {
        return null;
    }

    /**
     * Returns the inbound message entity stream if it exists.
     * 
     * @param size
     *            The expected entity size or -1 if unknown.
     * 
     * @return The inbound message entity stream if it exists.
     */
    public InputStream getInboundEntityStream(long size, boolean chunked) {
        InputStream result = null;

        if (chunked) {
            result = new ChunkedInputStream(getInboundStream());
        } else {
            result = new InputEntityStream(getInboundStream(), size);
        }

        return result;
    }

    /**
     * Returns the inbound message head channel if it exists.
     * 
     * @return The inbound message head channel if it exists.
     */
    public ReadableByteChannel getInboundHeadChannel() {
        return null;
    }

    /**
     * Returns the inbound message head stream if it exists.
     * 
     * @return The inbound message head stream if it exists.
     */
    public InputStream getInboundHeadStream() {
        return getInboundStream();
    }

    /**
     * Returns the response channel if it exists.
     * 
     * @return The response channel if it exists.
     */
    public WritableByteChannel getOutboundEntityChannel(boolean chunked) {
        return null;
    }

    /**
     * Returns the response entity stream if it exists.
     * 
     * @return The response entity stream if it exists.
     */
    public OutputStream getOutboundEntityStream(boolean chunked) {
        OutputStream result = getOutboundStream();

        if (chunked) {
            result = new ChunkedOutputStream(result);
        }

        return result;
    }

    /**
     * Returns the connection handler service.
     * 
     * @return The connection handler service.
     */
    protected ExecutorService getWorkerService() {
        return getHelper().getWorkerService();
    }

    /**
     * Reads the next messages. Only one message at a time if pipelining isn't
     * enabled.
     */
    @Override
    public void readMessages() {
        try {
            if (isPipelining()) {
                // TODO
                // boolean idempotentSequence = true;
            } else {
                boolean doRead = false;

                // We want to make sure that messages are read by one thread
                // at a time without blocking other concurrent threads during
                // the reading
                synchronized (getInboundMessages()) {
                    if (canRead()) {
                        doRead = true;
                        setInboundBusy(doRead);
                    }
                }

                if (doRead) {
                    readRequest();
                }
            }
        } catch (Exception e) {
            getLogger()
                    .log(
                            Level.FINE,
                            "Error while reading an HTTP request. Closing the connection: ",
                            e.getMessage());
            getLogger()
                    .log(
                            Level.FINE,
                            "Error while reading an HTTP request. Closing the connection.",
                            e);
            close(false);
        }

        // Immediately attempt to handle the next pending request, trying
        // to prevent a thread context switch.
        getHelper().handleNextRequest();
    }

    /**
     * Reads the next request sent by the client if available. Note that the
     * optional entity is not fully read.
     * 
     * @return The next request sent by the client if available.
     * @throws IOException
     */
    protected ConnectedRequest readRequest() throws IOException {
        ConnectedRequest result = null;
        String requestMethod = null;
        String requestUri = null;
        String version = null;
        Series<Parameter> requestHeaders = null;

        // Mark the inbound as busy
        setInboundBusy(true);

        // Parse the request method
        StringBuilder sb = new StringBuilder();
        int next = getInboundStream().read();
        while ((next != -1) && !HeaderUtils.isSpace(next)) {
            sb.append((char) next);
            next = getInboundStream().read();
        }

        if (next == -1) {
            throw new IOException(
                    "Unable to parse the request method. End of stream reached too early.");
        }

        requestMethod = sb.toString();
        sb.delete(0, sb.length());

        // Parse the request URI
        next = getInboundStream().read();
        while ((next != -1) && !HeaderUtils.isSpace(next)) {
            sb.append((char) next);
            next = getInboundStream().read();
        }

        if (next == -1) {
            throw new IOException(
                    "Unable to parse the request URI. End of stream reached too early.");
        }

        requestUri = sb.toString();
        if ((requestUri == null) || (requestUri.equals(""))) {
            requestUri = "/";
        }

        sb.delete(0, sb.length());

        // Parse the HTTP version
        next = getInboundStream().read();
        while ((next != -1) && !HeaderUtils.isCarriageReturn(next)) {
            sb.append((char) next);
            next = getInboundStream().read();
        }

        if (next == -1) {
            throw new IOException(
                    "Unable to parse the HTTP version. End of stream reached too early.");
        }
        next = getInboundStream().read();

        if (HeaderUtils.isLineFeed(next)) {
            version = sb.toString();
            sb.delete(0, sb.length());

            // Parse the headers
            Parameter header = HeaderUtils.readHeader(getInboundStream(), sb);
            while (header != null) {
                if (requestHeaders == null) {
                    requestHeaders = new Form();
                }

                requestHeaders.add(header);
                header = HeaderUtils.readHeader(getInboundStream(), sb);
            }
        } else {
            throw new IOException(
                    "Unable to parse the HTTP version. The carriage return must be followed by a line feed.");
        }

        if (HeaderUtils.isConnectionClose(requestHeaders)) {
            setState(ConnectionState.CLOSING);
        }

        // Create the HTTP request
        result = createRequest(getHelper().getContext(), this, requestMethod,
                requestUri, version, requestHeaders,
                createInboundEntity(requestHeaders), false, null);

        if (result != null) {
            if (result.isExpectingResponse()) {
                // Add it to the connection queue
                getInboundMessages().add(result);
            }

            // Add it to the helper queue
            getHelper().getPendingRequests().add(result);
        }

        return result;
    }

    /**
     * Indicates if the response should be chunked because its length is
     * unknown.
     * 
     * @param response
     *            The response to analyze.
     * @return True if the response should be chunked.
     */
    protected boolean shouldResponseBeChunked(Response response) {
        return (response.getEntity() != null)
                && (response.getEntity().getSize() == Representation.UNKNOWN_SIZE);
    }

    /**
     * Writes the entity headers for the given response.
     * 
     * @param response
     *            The response returned.
     */
    protected void writeEntityHeaders(Response response,
            Series<Parameter> headers) {
        HeaderUtils.addEntityHeaders(response.getEntity(), headers);
    }

    /**
     * Writes the next responses. Only one response at a time if pipelining
     * isn't enabled.
     */
    @Override
    public void writeMessages() {
        try {
            if (isPipelining()) {
                // TODO
            } else {
                Response response = null;

                // We want to make sure that responses are written in order
                // without blocking other concurrent threads during the writing
                synchronized (getOutboundMessages()) {
                    if (canWrite()) {
                        response = (Response) getOutboundMessages().poll();
                        setOutboundBusy((response != null));
                    }
                }

                writeResponse(response);

                if (getState() == ConnectionState.CLOSING) {
                    close(true);
                }
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING,
                    "Error while writing an HTTP response: ", e.getMessage());
            getLogger().log(Level.INFO, "Error while writing an HTTP response",
                    e);
        }
    }

    /**
     * Commits the changes to a handled uniform call back into the original HTTP
     * call. The default implementation first invokes the "addResponseHeaders"
     * then asks the "htppCall" to send the response back to the client.
     * 
     * @param response
     *            The high-level response.
     */
    @SuppressWarnings("unchecked")
    protected void writeResponse(Response response) {
        if (response != null) {
            // Prepare the headers
            Series<Parameter> headers = new Form();

            try {
                if ((response.getRequest().getMethod() != null)
                        && response.getRequest().getMethod()
                                .equals(Method.HEAD)) {
                    writeEntityHeaders(response, headers);
                    response.setEntity(null);
                } else if (Method.GET.equals(response.getRequest().getMethod())
                        && Status.SUCCESS_OK.equals(response.getStatus())
                        && (!response.isEntityAvailable())) {
                    writeEntityHeaders(response, headers);
                    getLogger()
                            .warning(
                                    "A response with a 200 (Ok) status should have an entity. Make sure that resource \""
                                            + response.getRequest()
                                                    .getResourceRef()
                                            + "\" returns one or sets the status to 204 (No content).");
                } else if (response.getStatus().equals(
                        Status.SUCCESS_NO_CONTENT)) {
                    writeEntityHeaders(response, headers);

                    if (response.isEntityAvailable()) {
                        getLogger()
                                .fine(
                                        "Responses with a 204 (No content) status generally don't have an entity. Only adding entity headers for resource \""
                                                + response.getRequest()
                                                        .getResourceRef()
                                                + "\".");
                        response.setEntity(null);
                    }
                } else if (response.getStatus().equals(
                        Status.SUCCESS_RESET_CONTENT)) {
                    if (response.isEntityAvailable()) {
                        getLogger()
                                .warning(
                                        "Responses with a 205 (Reset content) status can't have an entity. Ignoring the entity for resource \""
                                                + response.getRequest()
                                                        .getResourceRef()
                                                + "\".");
                        response.setEntity(null);
                    }
                } else if (response.getStatus().equals(
                        Status.REDIRECTION_NOT_MODIFIED)) {
                    writeEntityHeaders(response, headers);

                    if (response.isEntityAvailable()) {
                        getLogger()
                                .warning(
                                        "Responses with a 304 (Not modified) status can't have an entity. Only adding entity headers for resource \""
                                                + response.getRequest()
                                                        .getResourceRef()
                                                + "\".");
                        response.setEntity(null);
                    }
                } else if (response.getStatus().isInformational()) {
                    if (response.isEntityAvailable()) {
                        getLogger()
                                .warning(
                                        "Responses with an informational (1xx) status can't have an entity. Ignoring the entity for resource \""
                                                + response.getRequest()
                                                        .getResourceRef()
                                                + "\".");
                        response.setEntity(null);
                    }
                } else {
                    writeEntityHeaders(response, headers);

                    if ((response.getEntity() != null)
                            && !response.getEntity().isAvailable()) {
                        // An entity was returned but isn't really available
                        getLogger()
                                .warning(
                                        "A response with an unavailable entity was returned. Ignoring the entity for resource \""
                                                + response.getRequest()
                                                        .getResourceRef()
                                                + "\".");
                        response.setEntity(null);
                    }
                }

                // Add the response headers
                try {
                    addResponseHeaders(response, headers);

                    // Add user-defined extension headers
                    Series<Parameter> additionalHeaders = (Series<Parameter>) response
                            .getAttributes().get(
                                    HeaderConstants.ATTRIBUTE_HEADERS);
                    addAdditionalHeaders(headers, additionalHeaders);

                    // Set the server name again
                    headers.add(HeaderConstants.HEADER_SERVER, response
                            .getServerInfo().getAgent());
                } catch (Exception e) {
                    getLogger()
                            .log(
                                    Level.INFO,
                                    "Exception intercepted while adding the response headers",
                                    e);
                    response.setStatus(Status.SERVER_ERROR_INTERNAL);
                }

                // Write the response to the client
                writeResponse(response, headers);
            } catch (Exception e) {
                getLogger().log(Level.INFO,
                        "An exception occured writing the response entity", e);
                response.setStatus(Status.SERVER_ERROR_INTERNAL,
                        "An exception occured writing the response entity");
                response.setEntity(null);

                try {
                    writeResponse(response, headers);
                } catch (IOException ioe) {
                    getLogger().log(Level.WARNING,
                            "Unable to send error response", ioe);
                }
            } finally {
                if (response.getOnSent() != null) {
                    response.getOnSent()
                            .handle(response.getRequest(), response);
                }
            }
        }
    }

    /**
     * Writes the response back to the client. Commits the status, headers and
     * optional entity and send them over the network. The default
     * implementation only writes the response entity on the response stream or
     * channel. Subclasses will probably also copy the response headers and
     * status.
     * 
     * @param response
     *            The high-level response.
     * @throws IOException
     *             if the Response could not be written to the network.
     */
    protected void writeResponse(Response response, Series<Parameter> headers)
            throws IOException {
        if (response != null) {
            // Get the connector service to callback
            Representation responseEntity = response.getEntity();
            ConnectorService connectorService = ConnectorHelper
                    .getConnectorService(response.getRequest());

            if (connectorService != null) {
                connectorService.beforeSend(responseEntity);
            }

            try {
                writeResponseHead(response, headers);

                if (responseEntity != null) {
                    boolean chunked = HeaderUtils.isChunkedEncoding(headers);
                    WritableByteChannel responseEntityChannel = getOutboundEntityChannel(chunked);
                    OutputStream responseEntityStream = getOutboundEntityStream(chunked);
                    writeResponseBody(responseEntity, responseEntityChannel,
                            responseEntityStream);

                    if (responseEntityStream != null) {
                        responseEntityStream.flush();
                        responseEntityStream.close();
                    }
                }
            } catch (IOException ioe) {
                // The stream was probably already closed by the
                // connector. Probably OK, low message priority.
                getLogger()
                        .log(
                                Level.FINE,
                                "Exception while flushing and closing the entity stream.",
                                ioe);
            } finally {
                setOutboundBusy(false);

                if (responseEntity != null) {
                    responseEntity.release();
                }

                if (connectorService != null) {
                    connectorService.afterSend(responseEntity);
                }
            }
        }
    }

    /**
     * Effectively writes the response body. The entity to write is guaranteed
     * to be non null. Attempts to write the entity on the response channel or
     * response stream by default.
     * 
     * @param entity
     *            The representation to write as entity of the body.
     * @param responseEntityChannel
     *            The response entity channel or null if a stream is used.
     * @param responseEntityStream
     *            The response entity stream or null if a channel is used.
     * @throws IOException
     */
    protected void writeResponseBody(Representation entity,
            WritableByteChannel responseEntityChannel,
            OutputStream responseEntityStream) throws IOException {
        // Send the entity to the client
        if (responseEntityChannel != null) {
            entity.write(responseEntityChannel);
        } else if (responseEntityStream != null) {
            entity.write(responseEntityStream);
            responseEntityStream.flush();
        }
    }

    /**
     * Writes the response head to the given output stream.
     * 
     * @param response
     *            The response.
     * @param headStream
     *            The output stream to write to.
     * @throws IOException
     */
    protected void writeResponseHead(Response response,
            OutputStream headStream, Series<Parameter> headers)
            throws IOException {
        // Write the status line
        Protocol protocol = response.getRequest().getProtocol();
        String requestVersion = protocol.getVersion();
        String version = protocol.getTechnicalName() + '/'
                + ((requestVersion == null) ? "1.1" : requestVersion);
        headStream.write(version.getBytes());
        headStream.write(' ');
        headStream.write(Integer.toString(response.getStatus().getCode())
                .getBytes());
        headStream.write(' ');

        if (response.getStatus().getDescription() != null) {
            headStream.write(response.getStatus().getDescription().getBytes());
        } else {
            headStream.write(("Status " + response.getStatus().getCode())
                    .getBytes());
        }

        headStream.write(13); // CR
        headStream.write(10); // LF

        if (!isPersistent()) {
            headers.set(HeaderConstants.HEADER_CONNECTION, "close", true);
        }

        // Check if 'Transfer-Encoding' header should be set
        if (shouldResponseBeChunked(response)) {
            headers.add(HeaderConstants.HEADER_TRANSFER_ENCODING, "chunked");
        }

        // Write the response headers
        for (Parameter header : headers) {
            HeaderUtils.writeHeader(header, headStream);
        }

        // Write the end of the headers section
        headStream.write(13); // CR
        headStream.write(10); // LF
        headStream.flush();
    }

    /**
     * Writes the response status line and headers. Does nothing by default.
     * 
     * @param response
     *            The response.
     * @throws IOException
     */
    protected void writeResponseHead(Response response,
            Series<Parameter> headers) throws IOException {
        writeResponseHead(response, getOutboundStream(), headers);
    }

}
