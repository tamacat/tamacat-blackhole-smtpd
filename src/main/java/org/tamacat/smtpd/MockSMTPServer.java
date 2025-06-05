/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/
package org.tamacat.smtpd;

import org.apache.james.util.Port;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.subethamail.smtp.server.SMTPServer;

/**
 * https://github.com/apache/james-project/tree/master/server/mailet/mock-smtp-server
 */
public class MockSMTPServer {

    private final SMTPServer server;

    public MockSMTPServer() {
        this(new SMTPBehaviorRepository(), 25);
    }

    public MockSMTPServer(SMTPBehaviorRepository behaviorRepository, int port) {
    	SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
        
        this.server = new SMTPServer(ctx -> new MockMessageHandler(behaviorRepository));
        
        this.server.setPort(port);
    }

    void start() {
        if (!server.isRunning()) {
           server.start();
        }
    }

    Port getPort() {
        return Port.of(server.getPort());
    }

    void stop() {
        server.stop();
    }
}
