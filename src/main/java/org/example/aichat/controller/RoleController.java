package org.example.aichat.controller;

import lombok.RequiredArgsConstructor;
import org.example.aichat.dto.RoleCard;
import org.example.aichat.service.RoleCardService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 角色卡 CRUD 接口。
 *  - GET    /api/roles           列表
 *  - GET    /api/roles/{id}      详情
 *  - POST   /api/roles           新建
 *  - PUT    /api/roles/{id}      更新
 *  - DELETE /api/roles/{id}      删除
 */
@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleCardService roleCardService;

    @GetMapping
    public List<RoleCard> getAllRoles() {
        return roleCardService.listAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<RoleCard> getRoleById(@PathVariable Integer id) {
        return roleCardService.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public RoleCard createRole(@RequestBody RoleCard roleCard) {
        return roleCardService.create(roleCard);
    }

    @PutMapping("/{id}")
    public RoleCard updateRole(@PathVariable Integer id, @RequestBody RoleCard roleCard) {
        return roleCardService.update(id, roleCard);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRole(@PathVariable Integer id) {
        roleCardService.delete(id);
        return ResponseEntity.noContent().build();
    }
}