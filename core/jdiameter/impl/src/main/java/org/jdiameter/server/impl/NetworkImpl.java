/*
 * JBoss, Home of Professional Open Source
 * Copyright 2006, Red Hat, Inc. and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
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

package org.jdiameter.server.impl;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

import org.jdiameter.api.ApplicationAlreadyUseException;
import org.jdiameter.api.ApplicationId;
import org.jdiameter.api.InternalException;
import org.jdiameter.api.LocalAction;
import org.jdiameter.api.Message;
import org.jdiameter.api.NetworkReqListener;
import org.jdiameter.api.Peer;
import org.jdiameter.api.Realm;
import org.jdiameter.api.Selector;
import org.jdiameter.api.Statistic;
import org.jdiameter.api.URI;
import org.jdiameter.client.api.IMessage;
import org.jdiameter.common.api.statistic.IStatistic;
import org.jdiameter.common.api.statistic.IStatisticManager;
import org.jdiameter.common.api.statistic.IStatisticRecord;
import org.jdiameter.server.api.IMetaData;
import org.jdiameter.server.api.IMutablePeerTable;
import org.jdiameter.server.api.INetwork;
import org.jdiameter.server.api.IRouter;
import org.jdiameter.server.api.agent.IAgentConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 
 * @author erick.svenson@yahoo.com
 * @author <a href="mailto:brainslog@gmail.com"> Alexandre Mendonca </a>
 * @author <a href="mailto:baranowb@gmail.com"> Bartosz Baranowski </a>
 */
public class NetworkImpl implements INetwork {

  private static final Logger logger = LoggerFactory.getLogger(NetworkImpl.class);

  protected IMutablePeerTable manager;
  protected IRouter router;
  protected IMetaData metaData;
  private final ApplicationId commonAuthAppId = ApplicationId.createByAuthAppId(0, 0xffffffff);
  private final ApplicationId commonAccAppId = ApplicationId.createByAccAppId(0, 0xffffffff);
  private final ConcurrentHashMap<ApplicationId, NetworkReqListener> appIdToNetListener = new ConcurrentHashMap<ApplicationId, NetworkReqListener>();
  private final ConcurrentHashMap<Selector, NetworkReqListener> selectorToNetListener = new ConcurrentHashMap<Selector, NetworkReqListener>();

  protected IStatistic statistic;

  public NetworkImpl(IStatisticManager statisticFactory, IMetaData metaData, IRouter router) {
    this.router = router;
    this.metaData = metaData;

    IStatisticRecord nrlStat = statisticFactory.newCounterRecord(IStatisticRecord.Counters.RequestListenerCount, new IStatisticRecord.IntegerValueHolder() {
      public int getValueAsInt() {
        return appIdToNetListener.size();
      }

      public String getValueAsString() {
        return String.valueOf(getValueAsInt());
      }

    });
    IStatisticRecord nslStat = statisticFactory.newCounterRecord(IStatisticRecord.Counters.SelectorCount, new IStatisticRecord.IntegerValueHolder() {
      public int getValueAsInt() {
        return selectorToNetListener.size();
      }

      public String getValueAsString() {
        return String.valueOf(getValueAsInt());
      }

    });
    //no need to remove, this class lives with whole stack, until its destroyed.
    statistic = statisticFactory.newStatistic("network",IStatistic.Groups.Network, nrlStat, nslStat);
  }

  public void addNetworkReqListener(NetworkReqListener networkReqListener, ApplicationId... applicationId) throws ApplicationAlreadyUseException {
    for (ApplicationId a : applicationId) {
      if (appIdToNetListener.containsKey(commonAuthAppId) || appIdToNetListener.containsKey(commonAccAppId))
        throw new ApplicationAlreadyUseException(a + " already use by common application id");

      if (appIdToNetListener.containsKey(applicationId))
        throw new ApplicationAlreadyUseException(a + " already use");

      appIdToNetListener.put(a, networkReqListener);
      metaData.addApplicationId(a); // this has ALL config declared, we need currently deployed
      router.getRealmTable().addLocalApplicationId(a);
    }
  }

  public void addNetworkReqListener(NetworkReqListener listener, Selector<Message, ApplicationId>... selectors) {
    for (Selector<Message, ApplicationId> s : selectors) {
      selectorToNetListener.put(s, listener);
      ApplicationId ap = s.getMetaData();
      metaData.addApplicationId(ap);
      router.getRealmTable().addLocalApplicationId(ap);
    }
  }

  public void removeNetworkReqListener(ApplicationId... applicationId) {
    for (ApplicationId a : applicationId) {
      appIdToNetListener.remove(a);
      for (Selector<Message, ApplicationId> s : selectorToNetListener.keySet()) {
        if (s.getMetaData().equals(a)) return;
      }
      metaData.remApplicationId(a);
      router.getRealmTable().removeLocalApplicationId(a);
    }
  }

  public void removeNetworkReqListener(Selector<Message, ApplicationId>... selectors) {
    for (Selector<Message, ApplicationId> s : selectors) {
      selectorToNetListener.remove(s);
      if (appIdToNetListener.containsKey(s.getMetaData())) {
        return;
      }

      for (Selector<Message, ApplicationId> i : selectorToNetListener.keySet()) {
        if (i.getMetaData().equals(s.getMetaData())) {
          return;
        }
      }
      metaData.remApplicationId(s.getMetaData());
      router.getRealmTable().removeLocalApplicationId(s.getMetaData());
    }
  }

  public Peer addPeer(String name, String realm, boolean connecting) {
    if (manager != null) {
      try {
        return manager.addPeer(new URI(name), realm, connecting);
      }
      catch (Exception e) {
        logger.error("Failed to add peer with name[" + name + "] and realm[" + realm + "] (connecting=" + connecting + ")", e);
        return null;
      }
    }
    else {
      logger.debug("Failed to add peer with name[{}] and realm[{}] (connecting={}) as peer manager is null.", new Object[]{name, realm, connecting});
      return null;
    }
  }


  public boolean isWrapperFor(Class<?> aClass) throws InternalException {
    return false;
  }

  public <T> T unwrap(Class<T> aClass) throws InternalException {
    return null;
  }

  public Realm addRealm(String name, ApplicationId applicationId, LocalAction localAction, String agentConfiguration, boolean dynamic, long expirationTime) {
    try {
      //TODO: why oh why this method exists?
      return router.getRealmTable().addRealm(name, applicationId, localAction, agentConfiguration, dynamic, expirationTime, new String[0]);
    }
    catch (InternalException e) {
      logger.error("Failure on add realm operation.",e);
      return null;
    }
  }

  public Realm addRealm(String name, ApplicationId applicationId, LocalAction localAction, IAgentConfiguration agentConfiguration, boolean dynamic, long expirationTime) {
    try {
      //TODO: why oh why this method exists?
      return router.getRealmTable().addRealm(name, applicationId, localAction,agentConfiguration, dynamic, expirationTime, new String[0]);
    }
    catch (InternalException e) {
      logger.error("Failure on add realm operation.",e);
      return null;
    }
  }

  public Collection<Realm> remRealm(String name) {
    return router.getRealmTable().removeRealm(name);
  }

  public Statistic getStatistic() {
    return this.statistic;
  }

  public NetworkReqListener getListener(IMessage message) {
    if (message == null) return null;
    for (Selector<Message, ApplicationId> s : selectorToNetListener.keySet()) {
      boolean r = s.checkRule(message);
      if (r) return selectorToNetListener.get(s);
    }

    ApplicationId appId = message.getSingleApplicationId();
    if (appId == null) return null;
    if (appIdToNetListener.containsKey(commonAuthAppId))
      return appIdToNetListener.get(commonAuthAppId);
    else if (appIdToNetListener.containsKey(commonAccAppId))
      return appIdToNetListener.get(commonAccAppId);
    else
      return appIdToNetListener.get(appId);
  }

  public void setPeerManager(IMutablePeerTable manager) {
    this.manager = manager;
  }

}