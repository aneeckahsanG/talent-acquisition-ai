package com.talentai.config;

import com.talentai.auth.security.AppUserDetailsService;
import com.talentai.auth.security.JwtAuthenticationFilter;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;
import java.util.function.Supplier;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

	private final AppUserDetailsService userDetailsService;
	private final JwtAuthenticationFilter jwtAuthenticationFilter;

	@Value("${CORS_ALLOWED_ORIGINS:http://localhost:3000,http://localhost:5173}")
	private String allowedOrigins;

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
				.csrf(csrf -> csrf
						.ignoringRequestMatchers("/h2-console/**")
						.disable())
				.cors(cors -> cors.configurationSource(corsConfigurationSource()))
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/api/auth/login").permitAll()
						// Any signed-in user, interviewers included, may see who they are and change their password.
						.requestMatchers("/api/auth/me", "/api/auth/change-password").authenticated()
						.requestMatchers("/api/admin/**").permitAll()
						.requestMatchers("/api/public/**").permitAll()
						.requestMatchers("/api/public/offer-respond/**").permitAll()
						.requestMatchers("/api/public/interview/**").permitAll()
						.requestMatchers("/api/public/parse-resume").permitAll()
						.requestMatchers("/api/inbound/**").permitAll()
						.requestMatchers("/careers", "/careers/").permitAll()
						.requestMatchers("/mock-jobboard/**").permitAll()
						.requestMatchers("/api/referrals/public").permitAll()
						.requestMatchers("/actuator/health").permitAll()
						.requestMatchers("/h2-console/**").permitAll()
						// Account management is for administrators only.
						.requestMatchers("/api/users/**").hasRole("ADMIN")
						// Returns only the interviews assigned to the caller, so any signed-in user may call it.
						.requestMatchers("/api/interviewer/**").authenticated()
						// Everything else is the full recruiting app: any signed-in user except an interviewer.
						.anyRequest().access(SecurityConfig::notAnInterviewer)
				)
				// Unauthenticated requests (missing/expired token) → 401 so the
				// frontend can redirect to login (Spring's default is 403)
				.exceptionHandling(ex -> ex.authenticationEntryPoint(
						(request, response, authException) ->
								response.sendError(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))
				// H2 console uses iframes - must allow same-origin framing
				.headers(headers -> headers
						.frameOptions(frame -> frame.sameOrigin()))
				.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

		return http.build();
	}

	/** Signed in, and not an INTERVIEWER. Interviewers are limited to /api/interviewer/** and their own account. */
	private static AuthorizationDecision notAnInterviewer(Supplier<Authentication> authentication, RequestAuthorizationContext context) {
		Authentication auth = authentication.get();
		boolean signedIn = auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
		boolean interviewer = signedIn && auth.getAuthorities().stream()
				.anyMatch(a -> "ROLE_INTERVIEWER".equals(a.getAuthority()));
		return new AuthorizationDecision(signedIn && !interviewer);
	}

	@Bean
	public CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(List.of(allowedOrigins.split(",")));
		configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
		configuration.setAllowedHeaders(List.of("*"));
		configuration.setAllowCredentials(true);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);
		return source;
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	public AuthenticationProvider authenticationProvider() {
		DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
		authProvider.setUserDetailsService(userDetailsService);
		authProvider.setPasswordEncoder(passwordEncoder());
		return authProvider;
	}

	@Bean
	public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
		return config.getAuthenticationManager();
	}
}
