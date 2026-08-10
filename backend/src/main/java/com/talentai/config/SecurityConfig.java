package com.talentai.config;

import com.talentai.auth.security.AppUserDetailsService;
import com.talentai.auth.security.JwtAuthenticationFilter;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
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
						.requestMatchers("/api/auth/**").permitAll()
						.requestMatchers("/api/admin/**").permitAll()
						.requestMatchers("/api/public/**").permitAll()
						.requestMatchers("/api/public/parse-resume").permitAll()
						.requestMatchers("/api/inbound/**").permitAll()
						.requestMatchers("/careers", "/careers/").permitAll()
						.requestMatchers("/mock-jobboard/**").permitAll()
						.requestMatchers("/api/referrals/public").permitAll()
						.requestMatchers("/actuator/health").permitAll()
						.requestMatchers("/h2-console/**").permitAll()
						.anyRequest().authenticated()
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
