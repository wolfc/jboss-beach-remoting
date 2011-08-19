/*
 * JBoss, Home of Professional Open Source.
 * Copyright (c) 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.beach.remoting;

import org.jboss.marshalling.Marshalling;
import org.jboss.marshalling.SimpleDataInput;
import org.jboss.marshalling.SimpleDataOutput;
import org.jboss.remoting3.Channel;
import org.jboss.remoting3.CloseHandler;
import org.jboss.remoting3.Connection;
import org.jboss.remoting3.Endpoint;
import org.jboss.remoting3.MessageInputStream;
import org.jboss.remoting3.OpenListener;
import org.jboss.remoting3.Registration;
import org.jboss.remoting3.Remoting;
import org.jboss.remoting3.remote.RemoteConnectionProviderFactory;
import org.jboss.remoting3.security.SimpleServerAuthenticationProvider;
import org.jboss.remoting3.spi.NetworkServerProvider;
import org.jboss.sasl.JBossSaslProvider;
import org.junit.Test;
import org.xnio.IoFuture;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.Sequence;
import org.xnio.Xnio;
import org.xnio.channels.AcceptingChannel;
import org.xnio.channels.ConnectedStreamChannel;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.Security;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.jboss.beach.remoting.IoFutureHelper.future;
import static org.junit.Assert.assertTrue;
import static org.xnio.Options.SASL_POLICY_NOANONYMOUS;

/**
 * @author <a href="mailto:cdewolf@redhat.com">Carlo de Wolf</a>
 */
public class SimpleTestCase {
    @Test
    public void test1() throws IOException, URISyntaxException, ExecutionException, TimeoutException, InterruptedException {
        Security.addProvider(new JBossSaslProvider());
        // server
        final ExecutorService serverExecutor = Executors.newFixedThreadPool(4);
        {
            final OptionMap options = OptionMap.EMPTY;
            final Endpoint endpoint = Remoting.createEndpoint("endpoint", serverExecutor, options);
            final Xnio xnio = Xnio.getInstance();
            final Registration registration = endpoint.addConnectionProvider("remote", new RemoteConnectionProviderFactory(xnio), OptionMap.create(Options.SSL_ENABLED, false));
            final NetworkServerProvider serverProvider = endpoint.getConnectionProviderInterface("remote", NetworkServerProvider.class);
            final SocketAddress bindAddress = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 6999);
            final SimpleServerAuthenticationProvider authenticationProvider = new SimpleServerAuthenticationProvider();
            authenticationProvider.addUser("test", "localhost.localdomain", "test".toCharArray());
            final OptionMap serverOptions = OptionMap.create(Options.SASL_MECHANISMS, Sequence.of("ANONYMOUS"), Options.SASL_POLICY_NOANONYMOUS, Boolean.FALSE);
            final AcceptingChannel<? extends ConnectedStreamChannel> server = serverProvider.createServer(bindAddress, serverOptions, authenticationProvider);

            endpoint.registerService("channel", new OpenListener() {
                @Override
                public void channelOpened(Channel channel) {
                    channel.addCloseHandler(new CloseHandler<Channel>() {
                        @Override
                        public void handleClose(Channel closed, IOException exception) {
                            System.out.println("Bye " + closed);
                        }
                    });
                    Channel.Receiver handler = new Channel.Receiver() {
                        @Override
                        public void handleError(Channel channel, IOException error) {
                            try {
                                channel.close();
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                            throw new RuntimeException("NYI: .handleError");
                        }

                        @Override
                        public void handleEnd(Channel channel) {
                            try {
                                channel.close();
                            } catch (IOException e) {
                                // ignore
                            }
                        }

                        @Override
                        public void handleMessage(Channel channel, MessageInputStream message) {
                            channel.receiveMessage(this);
                            final SimpleDataInput input = new SimpleDataInput(Marshalling.createByteInput(message));
                            try {
                                final String txt = input.readUTF();
                                System.out.println(txt);
                                input.close();
                            } catch (IOException e) {
                                // log it
                                e.printStackTrace();
                                try {
                                    channel.writeShutdown();
                                } catch (IOException e1) {
                                    // ignore
                                }
                            }
                        }
                    };
                    channel.receiveMessage(handler);
                }

                @Override
                public void registrationTerminated() {
                    throw new RuntimeException("NYI: .registrationTerminated");
                }
            }, OptionMap.EMPTY);
        }

        // client
        {
            final Executor executor = Executors.newFixedThreadPool(4);
            final OptionMap options = OptionMap.EMPTY;
            final Endpoint endpoint = Remoting.createEndpoint("endpoint", executor, options);
            final Xnio xnio = Xnio.getInstance();
            final Registration registration = endpoint.addConnectionProvider("remote", new RemoteConnectionProviderFactory(xnio), OptionMap.create(Options.SSL_ENABLED, false));
            final OptionMap clientOptions = OptionMap.create(SASL_POLICY_NOANONYMOUS, Boolean.FALSE);
            final IoFuture<Connection> futureConnection = endpoint.connect(new URI("remote://localhost:6999"), clientOptions, new AnonymousCallbackHandler());
            final Connection connection = future(futureConnection).get(5, SECONDS);
            final Channel channel = future(connection.openChannel("channel", OptionMap.EMPTY)).get(5, SECONDS);
            channel.addCloseHandler(new CloseHandler<Channel>() {
                @Override
                public void handleClose(Channel closed, IOException exception) {
                    throw new RuntimeException("NYI: .handleClose");
                }
            });
            final SimpleDataOutput output = new SimpleDataOutput(Marshalling.createByteOutput(channel.writeMessage()));
            output.writeUTF("Hello world");
            output.close();
        }

        Thread.sleep(100);
        // wait for the server to process the message(s)
        serverExecutor.shutdown();
        final boolean terminated = serverExecutor.awaitTermination(5, SECONDS);
        assertTrue(terminated);
    }
}
