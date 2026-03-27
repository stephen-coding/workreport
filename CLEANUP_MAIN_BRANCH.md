# 清理 main 分支操作指南

## ✅ 已完成的操作

1. **本地 main 分支已删除** ✓
2. **当前所在分支**: develop

## ⏳ 待执行的操作（网络恢复后）

### 方式 1: 手动执行命令

```powershell
# 1. 推送 develop 分支的本地提交
git push origin develop

# 2. 删除远程 main 分支
git push origin --delete main

# 3. 验证分支状态
git branch -a
```

### 方式 2: 使用 PowerShell 脚本

运行当前目录下的 `cleanup-main-branch.ps1` 脚本：

```powershell
.\cleanup-main-branch.ps1
```

## 📊 最终分支结构

删除后将保留：

```
✓ master (生产分支)
✓ develop (开发分支，默认分支)
✗ main (已删除)
```

## 🔍 验证步骤

执行以下命令确认：

```bash
# 查看本地分支
git branch

# 查看远程分支
git branch -r

# 查看 GitHub 仓库的分支列表
# 访问：https://github.com/stephen-coding/workreport/branches
```

## ⚠️ 注意事项

1. **确保 develop 包含所有需要的提交**
   - develop 领先 origin/develop 1 个提交
   - 这是 README 对齐样式的修复提交

2. **GitHub 默认分支设置**
   - 如果 main 是 GitHub 的默认分支，需要先在 GitHub 设置中更改为 develop 或 master
   - 访问：Settings → Branches → Default branch → Change default branch

3. **保护分支规则**
   - 如果 main 有保护规则，删除后需要在 master 或 develop 上重新配置
   - 访问：Settings → Branches → Branch protection rules
