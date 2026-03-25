package org.example.aichat.controller;

import lombok.RequiredArgsConstructor;
import org.example.aichat.dto.User;
import org.example.aichat.mapper.UserMapper;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.HashMap;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserMapper userMapper;

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody User req) {
        Map<String, Object> result = new HashMap<>();
        User user = userMapper.findByUsername(req.getUsername());
        if (user == null) {
            // 自动注册
            user = new User();
            user.setUsername(req.getUsername());
            user.setPassword(req.getPassword());
            userMapper.insert(user);
        } else {
            if (!user.getPassword().equals(req.getPassword())) {
                result.put("success", false);
                result.put("message", "密码错误");
                return result;
            }
        }
        result.put("success", true);
        result.put("userId", user.getId());
        result.put("username", user.getUsername());
        return result;
    }
}