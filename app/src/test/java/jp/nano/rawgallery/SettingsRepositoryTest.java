package jp.nano.rawgallery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SettingsRepositoryTest {
  @Test
  void readsValuesFromTheExistingKeys() {
    SharedPreferences settings = preferences(Map.of("retentionDays", 42));
    assertEquals(42, SettingsRepository.retentionDays(settings));
  }

  @Test
  void keepsExistingFallback() {
    assertEquals(7, SettingsRepository.retentionDays(preferences(Map.of())));
  }

  @Test
  void keepsStrictTypesAndDoesNotNormalizeStoredValues() {
    SharedPreferences invalid = preferences(Map.of("retentionDays", 30L));
    assertThrows(ClassCastException.class, () -> SettingsRepository.retentionDays(invalid));
    assertEquals(366, SettingsRepository.retentionDays(preferences(Map.of("retentionDays", 366))));
  }

  private static SharedPreferences preferences(Map<String, Object> data) {
    return (SharedPreferences)
        Proxy.newProxyInstance(
            SharedPreferences.class.getClassLoader(),
            new Class<?>[] {SharedPreferences.class},
            (proxy, method, args) -> {
              if (!method.getName().equals("getInt")) throw new AssertionError(method.getName());
              return Integer.class.cast(data.getOrDefault(args[0], args[1]));
            });
  }
}
