/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.qpid.server.logging.messages;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.apache.qpid.server.logging.Outcome;

/**
 * Test QUE Log Messages
 */
public class QueueMessagesTest extends AbstractTestMessages
{
    @Test
    public void testQueueCreateSuccess()
    {
        final String attributes = "{\"type\": \"standard\"}";
        _logMessage = QueueMessages.CREATE("test", String.valueOf(Outcome.SUCCESS), attributes);
        final List<Object> log = performLog();
        final String[] expected = {"Create : \"test\" : ", String.valueOf(Outcome.SUCCESS), " : {\"type\": \"standard\"}"};
        validateLogMessage(log, "QUE-1001", expected);
    }

    @Test
    public void testQueueCreatedOwnerAutoDelete()
    {
        final String attributes = "{\"type\": \"standard\", \"owner\": \"guest\", \"lifetimePolicy\": \"DELETE_ON_CONNECTION_CLOSE\"}";
        _logMessage = QueueMessages.CREATE("test", String.valueOf(Outcome.SUCCESS), attributes);
        final List<Object> log = performLog();
        final String[] expected = {"Create : \"test\" : ", String.valueOf(Outcome.SUCCESS), attributes};
        validateLogMessage(log, "QUE-1001", expected);
    }

    @Test
    public void testQueueDelete()
    {
        _logMessage = QueueMessages.DELETE("test", String.valueOf(Outcome.SUCCESS));
        final List<Object> log = performLog();
        final String[] expected = {"Delete : \"test\" : SUCCESS"};

        validateLogMessage(log, "QUE-1002", expected);
    }
}
