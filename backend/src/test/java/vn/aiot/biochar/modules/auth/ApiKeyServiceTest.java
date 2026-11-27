package vn.aiot.biochar.modules.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ApiKeyServiceTest {

    @Test
    void hashesApiKeysWithSha256WithoutChangingTheInput() {
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ApiKeyService.hashApiKey("abc"));
        assertEquals(ApiKeyService.hashApiKey("abc"), ApiKeyService.hashApiKey("abc"));
    }
}
