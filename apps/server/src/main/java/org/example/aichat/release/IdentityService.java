package org.example.aichat.release;

import jakarta.servlet.http.HttpServletRequest;

/** Cloud account implementation is omitted from the open source artifact. */
public interface IdentityService {
  record Identity(int id, String username, String email, boolean admin) {}
  Identity authenticate(HttpServletRequest request);
}
