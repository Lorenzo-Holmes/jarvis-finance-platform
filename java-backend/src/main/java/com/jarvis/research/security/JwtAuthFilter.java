package com.jarvis.research.security;

import com.jarvis.research.config.JarvisProperties;
import com.jarvis.research.user.User;
import com.jarvis.research.user.UserRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器
 * 从 Authorization: Bearer <token> 解析用户
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final JarvisProperties props;
    private final UserRepository userRepository;

    @Autowired
    public JwtAuthFilter(JwtUtil jwtUtil, JarvisProperties props, UserRepository userRepository) {
        this.jwtUtil = jwtUtil;
        this.props = props;
        this.userRepository = userRepository;
    }

    /** 供不加载 Spring 容器的单元测试使用。 */
    public JwtAuthFilter(JwtUtil jwtUtil, JarvisProperties props) {
        this(jwtUtil, props, null);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = extractToken(request);
        if (token != null && jwtUtil.isValid(token)) {
            try {
                Claims claims = jwtUtil.parseToken(token);
                Long userId = Long.valueOf(claims.getSubject());
                String role = "USER";
                if (userRepository != null) {
                    User user = userRepository.findById(userId).orElse(null);
                    if (user == null || !user.isEnabled()) {
                        chain.doFilter(request, response);
                        return;
                    }
                    role = user.getRole();
                }
                List<SimpleGrantedAuthority> authorities = List.of(
                        new SimpleGrantedAuthority("ROLE_" + (role == null ? "USER" : role.toUpperCase())));
                var auth = new UsernamePasswordAuthenticationToken(userId, null, authorities);
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception e) {
                // invalid token -> anonymous
            }
        }
        chain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        String cookieName = props.getAuth().getCookieName();
        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
