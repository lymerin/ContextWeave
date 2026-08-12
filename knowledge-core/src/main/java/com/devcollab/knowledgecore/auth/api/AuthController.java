package com.devcollab.knowledgecore.auth.api;

import com.devcollab.knowledgecore.auth.application.AuthenticatedUser;
import com.devcollab.knowledgecore.auth.application.AuthenticationApplicationService;
import com.devcollab.knowledgecore.auth.api.RefreshCookieManager.RequestCredentials;
import com.devcollab.knowledgecore.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationApplicationService authenticationService;
    private final RefreshCookieManager refreshCookieManager;

    public AuthController(
            AuthenticationApplicationService authenticationService,
            RefreshCookieManager refreshCookieManager
    ) {
        this.authenticationService = authenticationService;
        this.refreshCookieManager = refreshCookieManager;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletResponse response
    ) {
        AuthenticatedUser authenticatedUser = authenticationService.register(
                request.username(),
                request.displayName(),
                request.password()
        );

        writeRefreshCookies(response, authenticatedUser);
        return AuthResponse.from(authenticatedUser);
    }

    @PostMapping("/login")
    public AuthResponse login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse response
    ) {
        AuthenticatedUser authenticatedUser = authenticationService.login(
                request.username(),
                request.password()
        );

        writeRefreshCookies(response, authenticatedUser);
        return AuthResponse.from(authenticatedUser);
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        RequestCredentials credentials =
                refreshCookieManager.readAndValidate(request);
        AuthenticatedUser authenticatedUser = authenticationService.refresh(
                credentials.refreshToken(),
                credentials.csrfToken()
        );

        writeRefreshCookies(response, authenticatedUser);
        return AuthResponse.from(authenticatedUser);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        Optional<RequestCredentials> credentials =
                refreshCookieManager.readForLogout(request);

        credentials.ifPresent(value -> authenticationService.logout(
                value.refreshToken(),
                value.csrfToken()
        ));

        refreshCookieManager.clear(response);
    }

    @GetMapping("/me")
    public CurrentUserResponse currentUser(
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return CurrentUserResponse.from(currentUser);
    }

    private void writeRefreshCookies(
            HttpServletResponse response,
            AuthenticatedUser authenticatedUser
    ) {
        refreshCookieManager.write(
                response,
                authenticatedUser.refreshToken(),
                authenticatedUser.csrfToken()
        );
    }
}
