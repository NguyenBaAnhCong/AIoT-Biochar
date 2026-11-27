package vn.aiot.biochar.modules.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AuthServiceTest {

    @Test
    void dummyPasswordHashIsWellFormedForUnknownUserChecks() throws ReflectiveOperationException {
        Field hashField = AuthService.class.getDeclaredField("DUMMY_HASH");
        hashField.setAccessible(true);
        String hash = (String) hashField.get(null);

        assertFalse(new BCryptPasswordEncoder().matches("not-the-password", hash));
    }
}
