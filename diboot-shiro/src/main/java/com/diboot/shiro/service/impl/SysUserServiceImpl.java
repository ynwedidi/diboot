package com.diboot.shiro.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.diboot.core.service.impl.BaseServiceImpl;
import com.diboot.core.util.S;
import com.diboot.core.util.V;
import com.diboot.core.vo.Status;
import com.diboot.shiro.entity.SysUser;
import com.diboot.shiro.entity.UserRole;
import com.diboot.shiro.enums.IUserType;
import com.diboot.shiro.exception.ShiroCustomException;
import com.diboot.shiro.mapper.SysUserMapper;
import com.diboot.shiro.service.SysUserService;
import com.diboot.shiro.service.UserRoleService;
import com.diboot.shiro.util.AuthHelper;
import com.diboot.shiro.vo.SysUserVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户相关Service
 * @author Yangzhao
 * @version v2.0
 * @date 2019/6/6
 */
@Service
@Slf4j
public class SysUserServiceImpl extends BaseServiceImpl<SysUserMapper, SysUser> implements SysUserService {

    @Autowired
    private UserRoleService userRoleService;

    @Override
    public SysUserVO getSysUser(Long id) {
        SysUserVO sysUserVO = super.getViewObject(id, SysUserVO.class);
        if(V.notEmpty(sysUserVO) && V.notEmpty(sysUserVO.getRoleList())){
            List<Long> roleIdList = new ArrayList();
            sysUserVO.getRoleList()
                    .stream()
                    .forEach(role -> roleIdList.add(role.getId()));
            sysUserVO.setRoleIdList(roleIdList);
        }
        return sysUserVO;
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean createSysUser(SysUser sysUser, IUserType iUserType) throws Exception{
        if (V.isEmpty(sysUser.getUsername()) || V.isEmpty(sysUser.getPassword())) {
            throw new ShiroCustomException(Status.FAIL_INVALID_PARAM, "用户名密码不能为空!");
        }
        LambdaQueryWrapper<SysUser> wrapper = Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, sysUser.getUsername())
                .eq(SysUser::getUserType, iUserType.getType());
        SysUser dbSysUser = super.getOne(wrapper);
        //校验数据库中数据是否已经存在
        if (V.notEmpty(dbSysUser)) {
            throw new ShiroCustomException(Status.FAIL_VALIDATION, "用户已存在！");
        }
        //构建 + 创建账户信息
        sysUser = this.buildSysUser(sysUser, iUserType);
        boolean success = super.createEntity(sysUser);
        if(!success){
            throw new ShiroCustomException(Status.FAIL_VALIDATION, "创建用户失败！");
        }
        //构建 + 创建（账户-角色）关系
        success = this.createUserRole(sysUser);
        if (!success) {
            throw new ShiroCustomException(Status.FAIL_VALIDATION, "创建用户失败！");
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateSysUser(SysUser user, IUserType iUserType) throws Exception {
        // 对密码进行处理
        if (V.notEmpty(user.getPassword())){
            this.buildSysUser(user, iUserType);
        }
        //更新用户信息
        boolean success = super.updateEntity(user);
        if(!success){
            throw new ShiroCustomException(Status.FAIL_VALIDATION, "更新用户失败！");
        }
        //组装需要删除 or 创建的（账户-角色）
        List<UserRole> createOrDeleteUserRoleList = new ArrayList<>();
        //构建用户角色关系
        this.buildCreateOrDeleteUserRoleList(user, iUserType, createOrDeleteUserRoleList);

        if (V.notEmpty(createOrDeleteUserRoleList)) {
            success = userRoleService.createOrUpdateOrDeleteEntities(createOrDeleteUserRoleList, createOrDeleteUserRoleList.size());
            if (!success) {
                throw new ShiroCustomException(Status.FAIL_VALIDATION, "更新用户失败！");
            }
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteSysUser(Long id, IUserType iUserType) throws Exception {
        //删除账户信息
        boolean success = super.deleteEntity(id);
        if(!success){
            throw new ShiroCustomException(Status.FAIL_VALIDATION, "删除用户失败！");
        }
        //删除账户绑定的角色信息
        Map<String, Object> criteria = new HashMap(){{
            put("userId", id);
            put("userType", iUserType.getType());
        }};

        if (userRoleService.deletePhysics(criteria)) {
            throw new ShiroCustomException(Status.FAIL_VALIDATION, "删除用户失败！");
        }
        return true;
    }

    /**
     * 创建账户角色关联关系
     * @param sysUser
     * @return
     */
    private boolean createUserRole(SysUser sysUser) {
        List<UserRole> urList = new ArrayList<>();
        if(V.notEmpty(sysUser.getRoleList())){
            sysUser.getRoleList().stream()
                    .forEach(role -> {
                        UserRole userRole = new UserRole();
                        userRole.setRoleId(role.getId());
                        userRole.setUserId(sysUser.getId());
                        userRole.setUserType(sysUser.getUserType());
                        urList.add(userRole);
                    });
        }
        return userRoleService.createEntities(urList);
    }

    /**
     * 构建需要创建或者删除的（账户-角色）集合
     * @param user
     * @param iUserType
     * @param createOrDeleteUserRoleList
     */
    private void buildCreateOrDeleteUserRoleList(SysUser user, IUserType iUserType, List<UserRole> createOrDeleteUserRoleList) {
        //获取数据库中用户角色信息
        LambdaQueryWrapper<UserRole> queryWrapper = Wrappers.<UserRole>lambdaQuery()
                .eq(UserRole::getUserType, iUserType.getType())
                .eq(UserRole::getUserId, user.getId());
        List<UserRole> dbUserRoleList = userRoleService.getEntityList(queryWrapper);

        //组装新的角色信息
        StringBuffer newRoleIdBuffer = new StringBuffer("_");
        if(V.notEmpty(user.getRoleList())){
            user.getRoleList()
                    .stream()
                    .forEach(role -> newRoleIdBuffer.append(role.getId()).append("_"));
        }

        //筛选出需要被删除的（账户-角色）
        StringBuffer dbRoleIdBuffer = new StringBuffer("_");
        if(V.notEmpty(dbUserRoleList)){
            dbUserRoleList.stream()
                    .forEach(userRole -> {
                        dbRoleIdBuffer.append(userRole.getRoleId()).append("_");
                        if (!newRoleIdBuffer.toString().contains(S.join("_", userRole.getRoleId(), "_"))) {
                            userRole.setDeleted(true);
                            createOrDeleteUserRoleList.add(userRole);
                        }
                    });
        }

        //对比数据库 筛选 + 构建 页面提交需要添加的角色信息
        if(V.notEmpty(user.getRoleList())){
            user.getRoleList()
                    .stream()
                    .forEach(role -> {
                        if (!dbRoleIdBuffer.toString().contains(S.join("_", role.getId(), "_"))) {
                            UserRole entity = new UserRole();
                            entity.setRoleId(role.getId());
                            entity.setUserId(user.getId());
                            entity.setUserType(iUserType.getType());
                            createOrDeleteUserRoleList.add(entity);
                        }
                    });
        }
    }

    /***
     * 设置加密密码相关的数据
     * @param sysUser
     */
    private SysUser buildSysUser(SysUser sysUser, IUserType userType) {
        String salt = AuthHelper.createSalt();
        String password = AuthHelper.encryptMD5(sysUser.getPassword(), salt, true);
        sysUser.setSalt(salt);
        sysUser.setDepartmentId(0L);
        sysUser.setUserType(userType.getType());
        sysUser.setPassword(password);
        return sysUser;
    }
}
