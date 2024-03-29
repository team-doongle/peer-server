package com.nodam.server.service;

import com.nodam.server.controller.AuthController;
import com.nodam.server.dto.LoginDTO;
import com.nodam.server.dto.UserDTO;
import com.nodam.server.security.SecurityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;


@Service
public class AuthService {
    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);
    @Autowired
    UserService userService;

    @Autowired
    SecurityService securityService;

    static private MajorCollegeMap majorCollegeMap = new MajorCollegeMap();

    public String createRefreshToken(LoginDTO loginDTO) {
        WebClient.Builder builder = WebClient.builder();
        Map sejongResponse = builder.build()
                .post()
                .uri("https://auth.imsejong.com/auth?method=ClassicSession")
                .body(Mono.just(new LoginDTO(loginDTO.getId(), loginDTO.getPw())), LoginDTO.class)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        Map result = (Map) sejongResponse.get("result");
        boolean isAuth = (boolean) result.get("is_auth");
        logger.info("createRefreshToken isAuth : " + isAuth);
        if (!isAuth)
            throw new RuntimeException("login failed");
        if (!userService.isUser(loginDTO.getId())) {
            logger.info("createRefreshToken first login " + loginDTO.getId());
            UserDTO userDTO = new UserDTO();
            userDTO.setId(loginDTO.getId());
            userDTO.setStudentNumber(Integer.parseInt(loginDTO.getId().substring(0,2)));
            Map body = (Map) result.get("body");
            userDTO.setName((String) body.get("name"));
            userDTO.setMajor((String) body.get("major"));
            userDTO.setCollege(majorCollegeMap.getCollege((String) body.get("major")));
            if (userDTO.getCollege() == null){
                userDTO.setCollege("알수없음");
                logger.info("createRefreshToken unknown college " + userDTO.getCollege() + " " + loginDTO.getId());
            }
            userDTO.setGrade(Integer.valueOf((String) body.get("grade")));
            userService.insertUser(userDTO);
        }

        return securityService.createToken(loginDTO.getId(), 7 * 24 * 60 * 60 * 1000L);
    }

    public String createAccessToken(String refreshToken) throws Exception {
        try {
            String subject = securityService.getSubject(refreshToken);
            return securityService.createToken(subject, 2 * 60 * 60 * 1000L);
        } catch (Exception e){
            throw new Exception("invalid auth");
        }
    }

    public ResponseEntity<?> refresh(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        try {
            for (Cookie cookie : cookies) {
                if (cookie.getName().equals("refreshToken")) {
                    Cookie refreshToken = cookie;
                    String accessToken = createAccessToken(refreshToken.getValue());

                    Map<String, String> map = new HashMap<>();
                    map.put("accessToken", accessToken);
                    return new ResponseEntity<>(map, HttpStatus.OK);
                }
            }
            throw new Exception("no refreshToken");
        } catch (Exception e){
            return new ResponseEntity<>("refresh failed", HttpStatus.NON_AUTHORITATIVE_INFORMATION);
        }
    }

    public String validateAccessToken(String accessToken) throws Exception{
        if (!accessToken.startsWith("Bearer"))
            throw new Exception("bad token");
        return securityService.getSubject(accessToken.substring(7));
    }
}
