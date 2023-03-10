package com.luckybag.luckybagbackend.service;


import com.luckybag.luckybagbackend.domain.DTO.LuckyBagDTO;
import com.luckybag.luckybagbackend.domain.DTO.SignUpDTO;
import com.luckybag.luckybagbackend.domain.LuckyBag;
import com.luckybag.luckybagbackend.domain.Member;
import com.luckybag.luckybagbackend.login.domain.dto.LoginDTO;
import com.luckybag.luckybagbackend.login.domain.dto.LogoutDTO;
import com.luckybag.luckybagbackend.login.domain.dto.TokenDTO;
import com.luckybag.luckybagbackend.login.jwt.TokenProvider;
import com.luckybag.luckybagbackend.repository.LuckyBagRepository;
import com.luckybag.luckybagbackend.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class MemberService {
    private final MemberRepository memberRepository;
    private final LuckyBagRepository luckyBagRepository;
    private final AuthenticationManagerBuilder authenticationManagerBuilder;
    private final TokenProvider tokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final RedisTemplate redisTemplate;

    public Member findEntityById(Long id) {
        return memberRepository.findById(id).get();
    }


    public TokenDTO login(LoginDTO loginDTO) {

        UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(loginDTO.getMemberId(), loginDTO.getMemberPassword());

        Authentication authentication = authenticationManagerBuilder.getObject().authenticate(authenticationToken);
        TokenDTO tokenDTO = tokenProvider.createToken(authentication);

        //refresh token Redis??? ??????
        redisTemplate.opsForValue().set("RT:" + authentication.getName(), tokenDTO.getRefreshToken(), tokenDTO.getRefreshTokenExpirationTime().getTime(), TimeUnit.MILLISECONDS);

        return tokenDTO;

    }

    public ResponseEntity<?> logout(LogoutDTO logoutDTO) {
        log.info("???????????? ??????");
        //accessToken ??????
        if (!tokenProvider.validateToken(logoutDTO.getAccessToken())) {
            return new ResponseEntity<>("????????? ???????????????.", HttpStatus.BAD_REQUEST);
        }
        //Access Token?????? authentication ????????????.
        Authentication authentication = tokenProvider.getAuthentication(logoutDTO.getAccessToken());
        //Redis?????? ?????? authentication?????? ????????? refresh token??? ?????? ?????? ????????????.
        if (redisTemplate.opsForValue().get("RT:" + authentication.getName())!= null) {
            redisTemplate.delete("RT:" + authentication.getName());
        }
        //?????? AccessToken ???????????? ????????? ?????? BlackList??? ????????????
        Long expiration = tokenProvider.getExpiration(logoutDTO.getAccessToken());
        redisTemplate.opsForValue().set(logoutDTO.getAccessToken(), "logout", expiration, TimeUnit.MILLISECONDS);
        return ResponseEntity.ok("???????????? ???????????????.");
    }

    public ResponseEntity<?> reissue(TokenDTO tokenDTO) {
        if (!tokenProvider.validateToken(tokenDTO.getRefreshToken())) {
            throw new RuntimeException("Refresh Token ????????? ???????????? ????????????.");
        }
        Authentication authentication = tokenProvider.getAuthentication(tokenDTO.getAccessToken());
        //Redis?????? ????????? ???????????? ????????? refresh token ?????? ????????????.
        String refreshToken = (String) redisTemplate.opsForValue().get("RT:" + authentication.getName());
        if (!refreshToken.equals(tokenDTO.getRefreshToken())) {
            throw new RuntimeException("Refresh Token ????????? ???????????? ????????????.");
        }
        //?????????????????? Redis??? refresh ????????? ?????? ??????
        if (ObjectUtils.isEmpty(refreshToken)) {
            throw new RuntimeException("???????????? ???????????????.");
        }
        //????????? ?????? ??????
        TokenDTO newToken = tokenProvider.createToken(authentication);
        //refreshToken Redis ????????????
        redisTemplate.opsForValue().set("RT:" + authentication.getName(), newToken.getRefreshToken(), newToken.getRefreshTokenExpirationTime().getTime(), TimeUnit.MILLISECONDS);

        return ResponseEntity.ok("?????? ?????? ??????");
    }

    public void signUp(SignUpDTO signUpDTO) {
        if (memberRepository.findByMemberId(signUpDTO.getMemberId()).isPresent()) {
            throw new IllegalArgumentException("?????? ???????????? ???????????????.");
        }
        Member signUpMember = Member.builder()
                .memberId(signUpDTO.getMemberId())
                .memberPassword(signUpDTO.getMemberPassword())
                .nickname(signUpDTO.getNickName())
                .roles(Collections.singletonList("USER"))
                .build();
        memberRepository.saveAndFlush(signUpMember);

        signUpMember.encodePassword(passwordEncoder);
    }

    public Long findId(LoginDTO loginDTO) {
        Member findMember = memberRepository.findByMemberId(loginDTO.getMemberId()).get();
        return findMember.getId();
    }

    public List<LuckyBagDTO> findEntitiesById(Long id) {
        List<LuckyBag> findLuckyBags = luckyBagRepository.findLuckyBagsByMemberId(id);
        return findLuckyBags.stream().map(luckyBag ->
                LuckyBagDTO.builder()
                        .luckyBagId(luckyBag.getId())
                        .comment(luckyBag.getComment())
                        .color(luckyBag.getColor())
                        .memberDTO(luckyBag.getMember().toDTO())
                        .build()).collect(Collectors.toList());
    }
}
