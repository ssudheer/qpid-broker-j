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
package org.apache.qpid.server.model.adapter;

import java.security.Principal;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.qpid.protocol.AMQConstant;
import org.apache.qpid.server.model.AbstractConfiguredObject;
import org.apache.qpid.server.model.ConfiguredObject;
import org.apache.qpid.server.model.Connection;
import org.apache.qpid.server.model.Port;
import org.apache.qpid.server.model.Session;
import org.apache.qpid.server.model.State;
import org.apache.qpid.server.model.StateTransition;
import org.apache.qpid.server.model.Transport;
import org.apache.qpid.server.model.VirtualHost;
import org.apache.qpid.server.protocol.AMQConnectionModel;
import org.apache.qpid.server.protocol.AMQSessionModel;
import org.apache.qpid.server.protocol.SessionModelListener;
import org.apache.qpid.server.util.Action;

public final class ConnectionAdapter extends AbstractConfiguredObject<ConnectionAdapter> implements Connection<ConnectionAdapter>,
                                                                                             SessionModelListener
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectionAdapter.class);

    private final Action _underlyingConnectionDeleteTask;
    private final AtomicBoolean _underlyingClosed = new AtomicBoolean(false);
    private final AMQConnectionModel _underlyingConnection;

    public ConnectionAdapter(final AMQConnectionModel conn)
    {
        super(parentsMap(conn.getPort()),createAttributes(conn));
        _underlyingConnection = conn;

        // Used to allow the protocol layers to tell the model they have been deleted
        _underlyingConnectionDeleteTask = new Action()
        {
            @Override
            public void performAction(final Object object)
            {
                conn.removeDeleteTask(this);
                _underlyingClosed.set(true);
                deleteAsync();
            }
        };
        conn.addDeleteTask(_underlyingConnectionDeleteTask);

        conn.addSessionListener(this);
        setState(State.ACTIVE);
    }

    public void virtualHostAssociated()
    {
        _underlyingConnection.getVirtualHost().registerConnection(this);
    }

    private static Map<String, Object> createAttributes(final AMQConnectionModel _connection)
    {
        Map<String,Object> attributes = new HashMap<String, Object>();
        attributes.put(ID, UUID.randomUUID());
        attributes.put(NAME, "[" + _connection.getConnectionId() + "] " + _connection.getRemoteAddressString().replaceAll("/", ""));
        attributes.put(DURABLE, false);
        return attributes;
    }

    @Override
    public String getClientId()
    {
        return _underlyingConnection.getClientId();
    }

    @Override
    public String getClientVersion()
    {
        return _underlyingConnection.getClientVersion();
    }

    @Override
    public boolean isIncoming()
    {
        return true;
    }

    @Override
    public String getLocalAddress()
    {
        return null;
    }

    @Override
    public String getPrincipal()
    {
        final Principal authorizedPrincipal = _underlyingConnection.getAuthorizedPrincipal();
        return authorizedPrincipal == null ? null : authorizedPrincipal.getName();
    }

    @Override
    public String getRemoteAddress()
    {
        return _underlyingConnection.getRemoteAddressString();
    }

    @Override
    public String getRemoteProcessName()
    {
        return null;
    }

    @Override
    public String getRemoteProcessPid()
    {
        return _underlyingConnection.getRemoteProcessPid();
    }

    @Override
    public long getSessionCountLimit()
    {
        return _underlyingConnection.getSessionCountLimit();
    }

    @Override
    public Transport getTransport()
    {
        return _underlyingConnection.getTransport();
    }

    @Override
    public Port getPort()
    {
        return _underlyingConnection.getPort();
    }

    @Override
    public VirtualHost<?,?,?> getVirtualHost()
    {
        return _underlyingConnection.getVirtualHost();
    }


    public Collection<Session> getSessions()
    {
        return getChildren(Session.class);
    }

    @StateTransition( currentState = State.ACTIVE, desiredState = State.DELETED)
    private ListenableFuture<Void> doDelete()
    {
        if (_underlyingClosed.get())
        {
            deleted();
            return Futures.immediateFuture(null);
        }
        else
        {
            final SettableFuture<Void> returnVal = SettableFuture.create();
            asyncCloseUnderlying().addListener(
                    new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            try
                            {
                                deleted();
                                setState(State.DELETED);
                            }
                            finally
                            {
                                returnVal.set(null);
                            }
                        }
                    }, getTaskExecutor().getExecutor()
                                    );
            return returnVal;
        }
    }

    @Override
    protected ListenableFuture<Void> beforeClose()
    {
        if (_underlyingClosed.get())
        {
            return Futures.immediateFuture(null);
        }
        else
        {

            return asyncCloseUnderlying();
        }

    }

    private ListenableFuture<Void> asyncCloseUnderlying()
    {
        final SettableFuture<Void> closeFuture = SettableFuture.create();
        _underlyingConnection.addDeleteTask(new Action()
        {
            @Override
            public void performAction(final Object object)
            {
                closeFuture.set(null);
            }
        });
        _underlyingConnection.removeDeleteTask(_underlyingConnectionDeleteTask);

        _underlyingConnection.closeAsync(AMQConstant.CONNECTION_FORCED, "Connection closed by external action");
        return closeFuture;
    }

    @Override
    protected void onClose()
    {
    }

    @Override
    public <C extends ConfiguredObject> ListenableFuture<C> addChildAsync(Class<C> childClass, Map<String, Object> attributes, ConfiguredObject... otherParents)
    {
        if(childClass == Session.class)
        {
            throw new IllegalStateException();
        }
        else
        {
            throw new IllegalArgumentException("Cannot create a child of class " + childClass.getSimpleName());
        }

    }

    @Override
    public long getBytesIn()
    {
        return _underlyingConnection.getDataReceiptStatistics().getTotal();
    }

    @Override
    public long getBytesOut()
    {
        return _underlyingConnection.getDataDeliveryStatistics().getTotal();
    }

    @Override
    public long getMessagesIn()
    {
        return _underlyingConnection.getMessageReceiptStatistics().getTotal();
    }

    @Override
    public long getMessagesOut()
    {
        return _underlyingConnection.getMessageDeliveryStatistics().getTotal();
    }

    @Override
    public long getLastIoTime()
    {
        return _underlyingConnection.getLastIoTime();
    }

    @Override
    public int getSessionCount()
    {
        return _underlyingConnection.getSessionModels().size();
    }

    @Override
    public void sessionAdded(final AMQSessionModel<?, ?> session)
    {
        SessionAdapter adapter = new SessionAdapter(this, session);
        adapter.create();
        childAdded(adapter);
    }

    @Override
    public void sessionRemoved(final AMQSessionModel<?, ?> session)
    {
        // SessionAdapter installs delete task to cause session model object to delete
    }

    @Override
    public AMQConnectionModel<?,?> getUnderlyingConnection()
    {
        return _underlyingConnection;
    }
}
