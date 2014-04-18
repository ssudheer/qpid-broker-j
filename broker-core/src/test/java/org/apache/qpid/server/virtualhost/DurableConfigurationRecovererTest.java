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
package org.apache.qpid.server.virtualhost;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyMap;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import org.apache.qpid.server.configuration.IllegalConfigurationException;
import org.apache.qpid.server.exchange.DirectExchange;
import org.apache.qpid.server.exchange.ExchangeImpl;
import org.apache.qpid.server.exchange.HeadersExchange;
import org.apache.qpid.server.exchange.TopicExchange;
import org.apache.qpid.server.logging.EventLogger;
import org.apache.qpid.server.model.Binding;
import org.apache.qpid.server.model.Broker;
import org.apache.qpid.server.model.ConfiguredObject;
import org.apache.qpid.server.model.ConfiguredObjectFactory;
import org.apache.qpid.server.model.Exchange;
import org.apache.qpid.server.model.Model;
import org.apache.qpid.server.model.Queue;
import org.apache.qpid.server.model.SystemContext;
import org.apache.qpid.server.model.UUIDGenerator;
import org.apache.qpid.server.model.VirtualHost;
import org.apache.qpid.server.plugin.ConfiguredObjectTypeFactory;
import org.apache.qpid.server.plugin.ExchangeType;
import org.apache.qpid.server.queue.AMQQueue;
import org.apache.qpid.server.store.ConfiguredObjectRecord;
import org.apache.qpid.server.store.ConfiguredObjectRecordImpl;
import org.apache.qpid.server.store.DurableConfigurationRecoverer;
import org.apache.qpid.server.store.DurableConfigurationStore;
import org.apache.qpid.server.store.DurableConfiguredObjectRecoverer;
import org.apache.qpid.server.store.StoreException;
import org.apache.qpid.server.store.UnresolvedConfiguredObject;
import org.apache.qpid.test.utils.QpidTestCase;

public class DurableConfigurationRecovererTest extends QpidTestCase
{
    private static final String VIRTUAL_HOST_NAME = "test";
    private static final UUID VIRTUAL_HOST_ID = UUID.randomUUID();
    private static final UUID QUEUE_ID = new UUID(0,0);
    private static final UUID TOPIC_EXCHANGE_ID = UUIDGenerator.generateExchangeUUID(TopicExchange.TYPE.getDefaultExchangeName(), VIRTUAL_HOST_NAME);
    private static final UUID DIRECT_EXCHANGE_ID = UUIDGenerator.generateExchangeUUID(DirectExchange.TYPE.getDefaultExchangeName(), VIRTUAL_HOST_NAME);
    private static final String CUSTOM_EXCHANGE_NAME = "customExchange";

    private DurableConfigurationRecoverer _durableConfigurationRecoverer;
    private VirtualHostImpl _vhost;
    private DurableConfigurationStore _store;
    private ConfiguredObjectFactory _configuredObjectFactory;
    private ConfiguredObjectTypeFactory _exchangeFactory;
    private ConfiguredObjectTypeFactory _queueFactory;
    private ConfiguredObjectTypeFactory _bindingFactory;

    @Override
    public void setUp() throws Exception
    {
        super.setUp();
        _configuredObjectFactory = mock(ConfiguredObjectFactory.class);
        _exchangeFactory = mock(ConfiguredObjectTypeFactory.class);
        _queueFactory = mock(ConfiguredObjectTypeFactory.class);
        _bindingFactory = mock(ConfiguredObjectTypeFactory.class);



        AMQQueue<?> queue = mock(AMQQueue.class);

        _vhost = mock(VirtualHostImpl.class);
        when(_vhost.getName()).thenReturn(VIRTUAL_HOST_NAME);
        final Broker<?> broker = mock(Broker.class);
        final SystemContext systemContext = mock(SystemContext.class);
        when(systemContext.getObjectFactory()).thenReturn(_configuredObjectFactory);
        when(broker.getObjectFactory()).thenReturn(_configuredObjectFactory);
        when(broker.getParent(eq(SystemContext.class))).thenReturn(systemContext);
        when(_vhost.getParent(eq(Broker.class))).thenReturn(broker);

        when(_vhost.getQueue(eq(QUEUE_ID))).thenReturn(queue);

        when(_configuredObjectFactory.getConfiguredObjectTypeFactory(eq(Exchange.class), anyMap())).thenReturn(_exchangeFactory);
        when(_configuredObjectFactory.getConfiguredObjectTypeFactory(eq(Queue.class), anyMap())).thenReturn(_queueFactory);
        when(_configuredObjectFactory.getConfiguredObjectTypeFactory(eq(Binding.class), anyMap())).thenReturn(_bindingFactory);



        final ArgumentCaptor<ConfiguredObjectRecord> recoveredExchange = ArgumentCaptor.forClass(ConfiguredObjectRecord.class);
        doAnswer(new Answer()
        {

            @Override
            public Object answer(final InvocationOnMock invocation) throws Throwable
            {
                ConfiguredObjectRecord exchangeRecord = recoveredExchange.getValue();
                ExchangeImpl exchange = mock(ExchangeImpl.class);
                UUID id = exchangeRecord.getId();
                String name = (String) exchangeRecord.getAttributes().get("name");
                when(exchange.getId()).thenReturn(id);
                when(exchange.getName()).thenReturn(name);
                when(_vhost.getExchange(eq(id))).thenReturn(exchange);
                when(_vhost.getExchange(eq(name))).thenReturn(exchange);

                UnresolvedConfiguredObject unresolved = mock(UnresolvedConfiguredObject.class);
                when(unresolved.resolve()).thenReturn(exchange);
                return unresolved;
            }
        }).when(_exchangeFactory).recover(recoveredExchange.capture(), any(ConfiguredObject.class));




        final ArgumentCaptor<ConfiguredObjectRecord> recoveredQueue = ArgumentCaptor.forClass(ConfiguredObjectRecord.class);
        doAnswer(new Answer()
        {

            @Override
            public Object answer(final InvocationOnMock invocation) throws Throwable
            {
                ConfiguredObjectRecord queueRecord = recoveredQueue.getValue();
                AMQQueue queue = mock(AMQQueue.class);
                UUID id = queueRecord.getId();
                String name = (String) queueRecord.getAttributes().get("name");
                when(queue.getId()).thenReturn(id);
                when(queue.getName()).thenReturn(name);
                when(_vhost.getQueue(eq(id))).thenReturn(queue);
                when(_vhost.getQueue(eq(name))).thenReturn(queue);

                UnresolvedConfiguredObject unresolved = mock(UnresolvedConfiguredObject.class);
                when(unresolved.resolve()).thenReturn(queue);

                Map args = queueRecord.getAttributes();
                if (args.containsKey(Queue.ALTERNATE_EXCHANGE))
                {
                    final UUID exchangeId = UUID.fromString(args.get(Queue.ALTERNATE_EXCHANGE).toString());
                    final ExchangeImpl exchange =
                            _vhost.getExchange(exchangeId);
                    when(queue.getAlternateExchange()).thenReturn(exchange);
                }

                return unresolved;
            }
        }).when(_queueFactory).recover(recoveredQueue.capture(), any(ConfiguredObject.class));


        final ArgumentCaptor<ConfiguredObjectRecord> recoveredBinding = ArgumentCaptor.forClass(ConfiguredObjectRecord.class);
        final ArgumentCaptor<ConfiguredObject> parent1 = ArgumentCaptor.forClass(ConfiguredObject.class);
        final ArgumentCaptor<ConfiguredObject> parent2 = ArgumentCaptor.forClass(ConfiguredObject.class);

        doAnswer(new Answer()
        {

            @Override
            public Object answer(final InvocationOnMock invocation) throws Throwable
            {
                ConfiguredObjectRecord queueRecord = recoveredBinding.getValue();
                Binding binding = mock(Binding.class);
                UUID id = queueRecord.getId();
                String name = (String) queueRecord.getAttributes().get("name");
                when(binding.getId()).thenReturn(id);
                when(binding.getName()).thenReturn(name);

                UnresolvedConfiguredObject unresolved = mock(UnresolvedConfiguredObject.class);
                when(unresolved.resolve()).thenReturn(binding);


                return unresolved;
            }
        }).when(_bindingFactory).recover(recoveredBinding.capture(), parent1.capture(), parent2.capture());



        DurableConfiguredObjectRecoverer[] recoverers = {
                new QueueRecoverer(_vhost),
                new ExchangeRecoverer(_vhost),
                new BindingRecoverer(_vhost)
        };

        final Map<String, DurableConfiguredObjectRecoverer> recovererMap= new HashMap<String, DurableConfiguredObjectRecoverer>();
        for(DurableConfiguredObjectRecoverer recoverer : recoverers)
        {
            recovererMap.put(recoverer.getType(), recoverer);
        }
        _durableConfigurationRecoverer =
                new DurableConfigurationRecoverer(_vhost.getName(), recovererMap,
                                                  new DefaultUpgraderProvider(_vhost), new EventLogger());

        _store = mock(DurableConfigurationStore.class);

    }

    public void testUpgradeEmptyStore() throws Exception
    {
        _durableConfigurationRecoverer.beginConfigurationRecovery(_store);
        assertEquals("Did not upgrade to the expected version",
                     Model.MODEL_VERSION,
                     _durableConfigurationRecoverer.completeConfigurationRecovery());
    }

    public void testUpgradeNewerStoreFails() throws Exception
    {
        String bumpedModelVersion = Model.MODEL_MAJOR_VERSION + "." + (Model.MODEL_MINOR_VERSION + 1);
        try
        {

            _durableConfigurationRecoverer.beginConfigurationRecovery(_store);
            _durableConfigurationRecoverer.configuredObject(getVirtualHostModelRecord(bumpedModelVersion));
            String newVersion = _durableConfigurationRecoverer.completeConfigurationRecovery();
            fail("Should not be able to start when config model is newer than current.  Actually upgraded to " + newVersion);
        }
        catch (IllegalStateException e)
        {
            // pass
        }
    }

    private ConfiguredObjectRecordImpl getVirtualHostModelRecord(
            String modelVersion)
    {
        ConfiguredObjectRecordImpl virtualHostRecord = new ConfiguredObjectRecordImpl(VIRTUAL_HOST_ID,
                                                                                      VirtualHost.class.getSimpleName(),
                                                                                      Collections.<String,Object>singletonMap("modelVersion", modelVersion));
        return virtualHostRecord;
    }

    public void testUpgradeRemovesBindingsToNonTopicExchanges() throws Exception
    {

        _durableConfigurationRecoverer.beginConfigurationRecovery(_store);
        _durableConfigurationRecoverer.configuredObject(getVirtualHostModelRecord("0.0"));

        _durableConfigurationRecoverer.configuredObject(new ConfiguredObjectRecordImpl(new UUID(1, 0),
                                                           "org.apache.qpid.server.model.Binding",
                                                           createBinding("key",
                                                                         "x-filter-jms-selector",
                                                                         "wibble"),
                                                           createBindingParents(DIRECT_EXCHANGE_ID, QUEUE_ID)));

        final ConfiguredObjectRecord[] expected = {
                new ConfiguredObjectRecordImpl(new UUID(1, 0), "Binding",
                        createBinding("key"))
        };

        verifyCorrectUpdates(expected);

        _durableConfigurationRecoverer.completeConfigurationRecovery();
    }



    public void testUpgradeOnlyRemovesSelectorBindings() throws Exception
    {

        _durableConfigurationRecoverer.beginConfigurationRecovery(_store);
        _durableConfigurationRecoverer.configuredObject(getVirtualHostModelRecord("0.0"));

        _durableConfigurationRecoverer.configuredObject(new ConfiguredObjectRecordImpl(new UUID(1, 0),
                                                           "org.apache.qpid.server.model.Binding",
                                                           createBinding("key",
                                                                         "x-filter-jms-selector",
                                                                         "wibble",
                                                                         "not-a-selector",
                                                                         "moo"),
                                                           createBindingParents(DIRECT_EXCHANGE_ID, QUEUE_ID)));


        final UUID customExchangeId = new UUID(3,0);

        _durableConfigurationRecoverer.configuredObject(new ConfiguredObjectRecordImpl(new UUID(2, 0),
                                                           "org.apache.qpid.server.model.Binding",
                                                           createBinding("key",
                                                                         "x-filter-jms-selector",
                                                                         "wibble",
                                                                         "not-a-selector",
                                                                         "moo"),
                                                           createBindingParents(customExchangeId,QUEUE_ID)));

        _durableConfigurationRecoverer.configuredObject(new ConfiguredObjectRecordImpl(customExchangeId,
                                                           "org.apache.qpid.server.model.Exchange",
                                                           createExchange(CUSTOM_EXCHANGE_NAME, HeadersExchange.TYPE)));

        final ConfiguredObjectRecord[] expected = {
                new ConfiguredObjectRecordImpl(new UUID(1, 0), "org.apache.qpid.server.model.Binding",
                        createBinding("key", "not-a-selector", "moo")),
                new ConfiguredObjectRecordImpl(new UUID(2, 0), "org.apache.qpid.server.model.Binding",
                        createBinding("key", "not-a-selector", "moo"))
        };

        verifyCorrectUpdates(expected);

        _durableConfigurationRecoverer.completeConfigurationRecovery();
    }


    public void testUpgradeKeepsBindingsToTopicExchanges() throws Exception
    {

        _durableConfigurationRecoverer.beginConfigurationRecovery(_store);
        _durableConfigurationRecoverer.configuredObject(getVirtualHostModelRecord("0.0"));

        _durableConfigurationRecoverer.configuredObject(new ConfiguredObjectRecordImpl(new UUID(1, 0),
                                                           "org.apache.qpid.server.model.Binding",
                                                           createBinding("key",
                                                                         "x-filter-jms-selector",
                                                                         "wibble"),
                                                           createBindingParents(TOPIC_EXCHANGE_ID,QUEUE_ID)));

        final ConfiguredObjectRecord[] expected = {
                new ConfiguredObjectRecordImpl(new UUID(1, 0), "Binding",
                        createBinding("key", "x-filter-jms-selector", "wibble"))
        };

        verifyCorrectUpdates(expected);

        _durableConfigurationRecoverer.completeConfigurationRecovery();
    }

    public void testUpgradeDoesNotRecur() throws Exception
    {

        _durableConfigurationRecoverer.beginConfigurationRecovery(_store);
        _durableConfigurationRecoverer.configuredObject(getVirtualHostModelRecord("0.0"));

        _durableConfigurationRecoverer.configuredObject(new ConfiguredObjectRecordImpl(new UUID(1, 0),
                                                           "Binding",
                                                           createBinding("key",
                                                                         "x-filter-jms-selector",
                                                                         "wibble"),
                                                           createBindingParents(DIRECT_EXCHANGE_ID,QUEUE_ID)));

        doThrow(new RuntimeException("Update Should not be called"))
                .when(_store).update(anyBoolean(), any(ConfiguredObjectRecordImpl[].class));

        _durableConfigurationRecoverer.completeConfigurationRecovery();
    }

    public void testFailsWithUnresolvedObjects()
    {
        _durableConfigurationRecoverer.beginConfigurationRecovery(_store);


        _durableConfigurationRecoverer.configuredObject(new ConfiguredObjectRecordImpl(new UUID(1, 0),
                                                        "Binding",
                                                        createBinding("key",
                                                                      "x-filter-jms-selector",
                                                                      "wibble"),
                                                        createBindingParents(new UUID(3,0),
                                                                             QUEUE_ID)));

        try
        {
            _durableConfigurationRecoverer.completeConfigurationRecovery();
            fail("Expected resolution to fail due to unknown object");
        }
        catch(IllegalConfigurationException e)
        {
            assertEquals("Durable configuration has unresolved dependencies", e.getMessage());
        }

    }

    public void testFailsWithUnknownObjectType()
    {
        _durableConfigurationRecoverer.beginConfigurationRecovery(_store);


        try
        {
            final Map<String, Object> emptyArguments = Collections.emptyMap();
            _durableConfigurationRecoverer.configuredObject(new ConfiguredObjectRecordImpl(new UUID(1, 0),
                                                            "Wibble", emptyArguments));
            _durableConfigurationRecoverer.completeConfigurationRecovery();
            fail("Expected resolution to fail due to unknown object type");
        }
        catch(IllegalConfigurationException e)
        {
            assertEquals("Unknown type for configured object: Wibble", e.getMessage());
        }


    }

    public void testRecoveryOfQueueAlternateExchange() throws Exception
    {

        final UUID queueId = new UUID(1, 0);
        final UUID exchangeId = new UUID(2, 0);

        _durableConfigurationRecoverer.beginConfigurationRecovery(_store);

        _durableConfigurationRecoverer.configuredObject(new ConfiguredObjectRecordImpl(queueId, Queue.class.getSimpleName(),
                                                        createQueue("testQueue", exchangeId)));
        _durableConfigurationRecoverer.configuredObject(new ConfiguredObjectRecordImpl(exchangeId,
                                                        org.apache.qpid.server.model.Exchange.class.getSimpleName(),
                                                        createExchange(CUSTOM_EXCHANGE_NAME, HeadersExchange.TYPE)));

        _durableConfigurationRecoverer.completeConfigurationRecovery();

        assertEquals(CUSTOM_EXCHANGE_NAME, _vhost.getQueue(queueId).getAlternateExchange().getName());
    }

    private void verifyCorrectUpdates(final ConfiguredObjectRecord[] expected) throws StoreException
    {
        doAnswer(new Answer()
        {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable
            {
                Object[] args = invocation.getArguments();
                final HashSet actual = new HashSet(Arrays.asList(args[1]));
                assertEquals("Updated records are not as expected", new HashSet(Arrays.asList(
                        expected)), actual);

                return null;
            }
        }).when(_store).update(anyBoolean(), any(ConfiguredObjectRecordImpl[].class));
    }

    private Map<String,Object> createBinding(String bindingKey, String... args)
    {
        Map<String, Object> binding = new LinkedHashMap<String, Object>();

        binding.put("name", bindingKey);
        Map<String,String> argumentMap = new LinkedHashMap<String, String>();
        if(args != null && args.length != 0)
        {
            String key = null;
            for(String arg : args)
            {
                if(key == null)
                {
                    key = arg;
                }
                else
                {
                    argumentMap.put(key, arg);
                    key = null;
                }
            }
        }
        binding.put(Binding.ARGUMENTS, argumentMap);
        return binding;
    }

    private Map<String,ConfiguredObjectRecord> createBindingParents(UUID exchangeId, UUID queueId)
    {
        Map<String,ConfiguredObjectRecord> parents = new HashMap<String, ConfiguredObjectRecord>();
        parents.put("Exchange", new ConfiguredObjectRecordImpl(exchangeId,"Exchange",Collections.<String,Object>emptyMap()));
        parents.put("Queue", new ConfiguredObjectRecordImpl(queueId,"Queue",Collections.<String,Object>emptyMap()));

        return parents;
    }


    private Map<String, Object> createExchange(String name, ExchangeType<HeadersExchange> type)
    {
        Map<String, Object> exchange = new LinkedHashMap<String, Object>();

        exchange.put(org.apache.qpid.server.model.Exchange.NAME, name);
        exchange.put(org.apache.qpid.server.model.Exchange.TYPE, type.getType());

        return exchange;

    }


    private Map<String, Object> createQueue(String name, UUID alternateExchangeId)
    {
        Map<String, Object> queue = new LinkedHashMap<String, Object>();

        queue.put(Queue.NAME, name);
        if(alternateExchangeId != null)
        {
            queue.put(Queue.ALTERNATE_EXCHANGE, alternateExchangeId.toString());
        }

        return queue;

    }


}
