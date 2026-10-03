package com.squadpulse.auth;

import com.squadpulse.common.UnauthorizedException;

/**
 * The caller's access token is valid, but the user it names can no longer act: deactivated,
 * deleted, or not found in the token's club. Same message as a request without any token (see
 * {@link SecurityConfig#AUTHENTICATION_REQUIRED_MESSAGE}), so the response never says which.
 */
class CurrentUserUnavailableException extends UnauthorizedException {

  CurrentUserUnavailableException() {
    super(SecurityConfig.AUTHENTICATION_REQUIRED_MESSAGE);
  }
}
