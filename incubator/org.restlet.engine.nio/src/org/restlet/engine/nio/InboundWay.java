package org.restlet.engine.nio;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.logging.Level;

import org.restlet.Context;
import org.restlet.Response;
import org.restlet.data.Form;
import org.restlet.data.Parameter;
import org.restlet.engine.http.header.HeaderReader;
import org.restlet.engine.http.header.HeaderUtils;
import org.restlet.representation.EmptyRepresentation;
import org.restlet.representation.InputRepresentation;
import org.restlet.representation.ReadableRepresentation;
import org.restlet.representation.Representation;
import org.restlet.util.Series;

public class InboundWay extends Way {

    /**
     * Constructor.
     * 
     * @param connection
     *            The parent connection.
     */
    public InboundWay(Connection<?> connection) {
        super(connection);
        // this.inboundChannel = new InboundStream(getSocket()
        // .getInputStream());

    }

    /**
     * Returns the message entity if available.
     * 
     * @param headers
     *            The headers to use.
     * @return The inbound message if available.
     */
    public Representation createEntity(Series<Parameter> headers) {
        Representation result = null;
        long contentLength = HeaderUtils.getContentLength(headers);
        boolean chunkedEncoding = HeaderUtils.isChunkedEncoding(headers);

        // In some cases there is an entity without a content-length header
        boolean connectionClosed = HeaderUtils.isConnectionClose(headers);

        // Create the representation
        if ((contentLength != Representation.UNKNOWN_SIZE && contentLength != 0)
                || chunkedEncoding || connectionClosed) {
            InputStream inboundEntityStream = getEntityStream(contentLength,
                    chunkedEncoding);
            ReadableByteChannel inboundEntityChannel = getEntityChannel(
                    contentLength, chunkedEncoding);

            if (inboundEntityStream != null) {
                result = new InputRepresentation(inboundEntityStream, null,
                        contentLength) {

                    @Override
                    public String getText() throws IOException {
                        try {
                            return super.getText();
                        } catch (IOException ioe) {
                            throw ioe;
                        } finally {
                            release();
                        }
                    }

                    @Override
                    public void release() {
                        super.release();
                        setBusy(false);
                    }
                };
            } else if (inboundEntityChannel != null) {
                result = new ReadableRepresentation(inboundEntityChannel, null,
                        contentLength) {
                    @Override
                    public void release() {
                        super.release();
                        setBusy(false);
                    }
                };
            }

            result.setSize(contentLength);
        } else {
            result = new EmptyRepresentation();

            // Mark the inbound as free so new messages can be read if possible
            setBusy(false);
        }

        if (headers != null) {
            try {
                result = HeaderUtils.copyResponseEntityHeaders(headers, result);
            } catch (Throwable t) {
                getLogger().log(Level.WARNING,
                        "Error while parsing entity headers", t);
            }
        }

        return result;
    }

    /**
     * Creates the representation wrapping the given stream.
     * 
     * @param stream
     *            The response input stream.
     * @return The wrapping representation.
     */
    protected Representation createRepresentation(InputStream stream) {
        return new InputRepresentation(stream, null);
    }

    /**
     * Returns the representation wrapping the given channel.
     * 
     * @param channel
     *            The response channel.
     * @return The wrapping representation.
     */
    protected Representation createRepresentation(
            java.nio.channels.ReadableByteChannel channel) {
        return new org.restlet.representation.ReadableRepresentation(channel,
                null);
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
     * @return The created request.
     */
    protected ConnectedRequest createRequest(Context context,
            ServerConnection connection, String methodName, String resourceUri,
            String version) {
        return new ConnectedRequest(getHelper().getContext(), this, methodName,
                resourceUri, version);
    }

    /**
     * Returns the inbound message entity channel if it exists.
     * 
     * @param size
     *            The expected entity size or -1 if unknown.
     * 
     * @return The inbound message entity channel if it exists.
     */
    public ReadableByteChannel getEntityChannel(long size, boolean chunked) {
        return null; // getSocketChannel();
    }

    /**
     * Returns the inbound message entity stream if it exists.
     * 
     * @param size
     *            The expected entity size or -1 if unknown.
     * 
     * @return The inbound message entity stream if it exists.
     */
    public InputStream getEntityStream(long size, boolean chunked) {
        // InputStream result = null;
        //
        // if (chunked) {
        // result = new ChunkedInputStream(this, getInboundStream());
        // } else if (size >= 0) {
        // result = new SizedInputStream(this, getInboundStream(), size);
        // } else {
        // result = new ClosingInputStream(this, getInboundStream());
        // }
        //
        // return result;
        return null;
    }

    /**
     * Reads available bytes from the socket channel.
     * 
     * @return The number of bytes read.
     * @throws IOException
     */
    public int readBytes() throws IOException {
        getBuffer().clear();
        int result = getConnection().getSocketChannel().read(getBuffer());
        getBuffer().flip();
        return result;
    }

    /**
     * Reads the next request sent by the client if available. Note that the
     * optional entity is not fully read.
     * 
     * @throws IOException
     */
    @Override
    public void readMessage() throws IOException {
        if (getMessageState() == null) {
            setMessageState(WayMessageState.START_LINE);
            getBuilder().delete(0, getBuilder().length());
        }

        while (getBuffer().hasRemaining()) {
            if (getMessageState() == WayMessageState.START_LINE) {
                readMessageStart();
            } else if (getMessageState() == WayMessageState.HEADERS) {
                readMessageHeaders();
            }
        }
    }

    /**
     * Reads the next message received via the inbound stream or channel. Note
     * that the optional entity is not fully read.
     * 
     * @throws IOException
     */
    protected void readMessage() throws IOException {

    }

    /**
     * Reads a message header.
     * 
     * @return The new message header or null.
     * @throws IOException
     */
    protected Parameter readMessageHeader() throws IOException {
        Parameter header = HeaderReader.readHeader(getBuilder());
        getBuilder().delete(0, getBuilder().length());
        return header;
    }

    @Override
    public void readMessageHeaders() throws IOException {
        if (readMessageLine()) {
            ConnectedRequest request = (ConnectedRequest) getMessage()
                    .getRequest();
            Series<Parameter> headers = request.getHeaders();
            Parameter header = readMessageHeader();

            while (header != null) {
                if (headers == null) {
                    headers = new Form();
                }

                headers.add(header);

                if (readMessageLine()) {
                    header = readMessageHeader();

                    // End of headers
                    if (header == null) {
                        // Check if the client wants to close the connection
                        if (HeaderUtils.isConnectionClose(headers)) {
                            setState(ConnectionState.CLOSING);
                        }

                        // Check if an entity is available
                        Representation entity = createInboundEntity(headers);

                        if (entity instanceof EmptyRepresentation) {
                            setMessageState(WayMessageState.END);
                        } else {
                            request.setEntity(entity);
                            setMessageState(WayMessageState.BODY);
                        }

                        // Update the response
                        getMessage().getServerInfo().setAddress(
                                getHelper().getHelped().getAddress());
                        getMessage().getServerInfo().setPort(
                                getHelper().getHelped().getPort());

                        if (request != null) {
                            if (request.isExpectingResponse()) {
                                // Add it to the connection queue
                                getMessages().add(getMessage());
                            }

                            // Add it to the helper queue
                            getHelper().getInboundMessages().add(getMessage());
                        }
                    }
                } else {
                    // Missing characters
                }
            }

            request.setHeaders(headers);
        }
    }

    /**
     * Reads the header lines of the current message received.
     * 
     * @throws IOException
     */
    protected void readMessageHeaders() throws IOException {

    }

    /**
     * Read the current message line (start line or header line).
     * 
     * @return True if the message line was fully read.
     * @throws IOException
     */
    protected boolean readMessageLine() throws IOException {
        boolean result = false;
        int next;

        while (!result && getBuffer().hasRemaining()) {
            next = (int) getBuffer().get();

            if (HeaderUtils.isCarriageReturn(next)) {
                next = (int) getBuffer().get();

                if (HeaderUtils.isLineFeed(next)) {
                    result = true;
                } else {
                    throw new IOException(
                            "Missing carriage return character at the end of HTTP line");
                }
            } else {
                getBuilder().append((char) next);
            }
        }

        return result;
    }

    /**
     * Reads inbound messages from the socket. Only one message at a time if
     * pipelining isn't enabled.
     */
    public void readMessages() {
        try {
            if (canRead()) {
                int result = readBytes();

                while (getBuffer().hasRemaining()) {
                    readMessage();

                    if (!getBuffer().hasRemaining()) {
                        // Attempt to read more
                        result = readBytes();
                    }
                }

                if (result == -1) {
                    close(true);
                }
            }
        } catch (Exception e) {
            getLogger()
                    .log(
                            Level.INFO,
                            "Error while reading a message. Closing the connection.",
                            e);
            close(false);
        }
    }

    @Override
    public void readMessageStart() throws IOException {
        if (readMessageLine()) {
            String requestMethod = null;
            String requestUri = null;
            String version = null;

            int i = 0;
            int start = 0;
            int size = getBuilder().length();
            char next;

            if (size == 0) {
                // Skip leading empty lines per HTTP specification
            } else {
                // Parse the request method
                for (i = start; (requestMethod == null) && (i < size); i++) {
                    next = getBuilder().charAt(i);

                    if (HeaderUtils.isSpace(next)) {
                        requestMethod = getBuilder().substring(start, i);
                        start = i + 1;
                    }
                }

                if ((requestMethod == null) || (i == size)) {
                    throw new IOException(
                            "Unable to parse the request method. End of line reached too early.");
                }

                // Parse the request URI
                for (i = start; (requestUri == null) && (i < size); i++) {
                    next = getBuilder().charAt(i);

                    if (HeaderUtils.isSpace(next)) {
                        requestUri = getBuilder().substring(start, i);
                        start = i + 1;
                    }
                }

                if (i == size) {
                    throw new IOException(
                            "Unable to parse the request URI. End of line reached too early.");
                }

                if ((requestUri == null) || (requestUri.equals(""))) {
                    requestUri = "/";
                }

                // Parse the protocol version
                for (i = start; (version == null) && (i < size); i++) {
                    next = getBuilder().charAt(i);
                }

                if (i == size) {
                    version = getBuilder().substring(start, i);
                    start = i + 1;
                }

                if (version == null) {
                    throw new IOException(
                            "Unable to parse the protocol version. End of line reached too early.");
                }

                ConnectedRequest request = createRequest(getHelper()
                        .getContext(), this, requestMethod, requestUri, version);
                Response response = getHelper().createResponse(request);
                setMessage(response);

                setMessageState(WayMessageState.HEADERS);
                getBuilder().delete(0, getBuilder().length());
            }
        } else {
            // We need more characters before parsing
        }
    }

    /**
     * Reads the start line of the current message received.
     * 
     * @throws IOException
     */
    protected void readMessageStart() throws IOException {

    }

    @Override
    public void registerInterest(Selector selector) {
        int socketInterest = 0;

        try {
            if (getIoState() == WayIoState.READ_INTEREST) {
                socketInterest = socketInterest | SelectionKey.OP_READ;
            }

            if (socketInterest > 0) {
                getConnection().getSocketChannel().register(selector,
                        socketInterest, this);
            }
        } catch (ClosedChannelException cce) {
            getLogger()
                    .log(
                            Level.WARNING,
                            "Unable to register NIO interest operations for this connection",
                            cce);
            getConnection().setState(ConnectionState.CLOSING);
        }
    }

}
