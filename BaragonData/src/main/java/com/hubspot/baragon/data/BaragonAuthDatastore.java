package com.hubspot.baragon.data;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.collect.Maps;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hubspot.baragon.models.BaragonAuthKey;
import org.apache.curator.framework.CuratorFramework;

@Singleton
public class BaragonAuthDatastore extends AbstractDataStore {
  public static final String AUTH_KEYS_PATH = "/auth";
  public static final String AUTH_KEY_PATH = AUTH_KEYS_PATH + "/%s";

  @Inject
  public BaragonAuthDatastore(CuratorFramework curatorFramework, ObjectMapper objectMapper) {
    super(curatorFramework, objectMapper);
  }

  public void addAuthKey(BaragonAuthKey authKey) {
    writeToZk(String.format(AUTH_KEY_PATH, authKey.getValue()), authKey);
  }

  public Optional<BaragonAuthKey> expireAuthKey(String key) {
    final String path = String.format(AUTH_KEY_PATH, key);

    final Optional<BaragonAuthKey> maybeAuthKey = readFromZk(path, BaragonAuthKey.class);

    if (!maybeAuthKey.isPresent()) {
      return maybeAuthKey;
    }

    final BaragonAuthKey expiredAuthKey = BaragonAuthKey.expire(maybeAuthKey.get());

    writeToZk(path, expiredAuthKey);

    return Optional.of(expiredAuthKey);
  }

  public Map<String, BaragonAuthKey> getAuthKeyMap() {
    Map<String, BaragonAuthKey> keyMap = Maps.newHashMap();

    for (String node : getChildren(AUTH_KEYS_PATH)) {
      final Optional<BaragonAuthKey> maybeAuthKey = readFromZk(String.format(AUTH_KEY_PATH, node), BaragonAuthKey.class);

      if (maybeAuthKey.isPresent()) {
        keyMap.put(maybeAuthKey.get().getValue(), maybeAuthKey.get());
      }
    }

    return keyMap;
  }

  public Optional<BaragonAuthKey> getAuthKeyInfo(String key) {
    return readFromZk(String.format(AUTH_KEY_PATH, key), BaragonAuthKey.class);
  }
}
