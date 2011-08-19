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
import org.jboss.marshalling.SimpleDataOutput;
import org.jboss.remoting3.Channel;
import org.jboss.remoting3.CloseHandler;
import org.jboss.remoting3.Connection;
import org.jboss.remoting3.Endpoint;
import org.jboss.remoting3.Registration;
import org.jboss.remoting3.Remoting;
import org.jboss.remoting3.remote.RemoteConnectionProviderFactory;
import org.jboss.sasl.JBossSaslProvider;
import org.junit.Test;
import org.xnio.IoFuture;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.Xnio;

import java.io.IOException;
import java.net.URI;
import java.security.Security;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.jboss.beach.remoting.IoFutureHelper.future;
import static org.xnio.Options.SASL_POLICY_NOANONYMOUS;

/**
 * @author <a href="mailto:cdewolf@redhat.com">Carlo de Wolf</a>
 */
public class CallAS7TestCase {
    @Test
    public void test1() throws Exception {
        Security.addProvider(new JBossSaslProvider());
        final Executor executor = Executors.newFixedThreadPool(4);
        final OptionMap options = OptionMap.EMPTY;
        final Endpoint endpoint = Remoting.createEndpoint("endpoint", executor, options);
        final Xnio xnio = Xnio.getInstance();
        final Registration registration = endpoint.addConnectionProvider("remote", new RemoteConnectionProviderFactory(xnio), OptionMap.create(Options.SSL_ENABLED, false));
        final OptionMap clientOptions = OptionMap.create(SASL_POLICY_NOANONYMOUS, Boolean.FALSE);
        final IoFuture<Connection> futureConnection = endpoint.connect(new URI("remote://localhost:9999"), clientOptions, new AnonymousCallbackHandler());
        final Connection connection = future(futureConnection).get(5, SECONDS);
        final Channel channel = future(connection.openChannel("ejb3", OptionMap.EMPTY)).get(5, SECONDS);
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
}
