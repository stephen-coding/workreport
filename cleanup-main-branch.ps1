# PowerShell 脚本：清理 main 分支

Write-Host "🚀 开始清理 main 分支..." -ForegroundColor Green

# 1. 检查当前分支
$currentBranch = git rev-parse --abbrev-ref HEAD
if ($currentBranch -ne "develop") {
    Write-Host "❌ 请先切换到 develop 分支" -ForegroundColor Red
    Write-Host "   运行：git checkout develop" -ForegroundColor Yellow
    exit 1
}

Write-Host "✓ 当前分支：$currentBranch" -ForegroundColor Green

# 2. 推送 develop 的本地提交
Write-Host "`n📤 推送 develop 分支..." -ForegroundColor Cyan
git push origin develop
if ($LASTEXITCODE -eq 0) {
    Write-Host "✓ develop 推送成功" -ForegroundColor Green
} else {
    Write-Host "⚠ develop 推送失败，请检查网络连接" -ForegroundColor Yellow
}

# 3. 删除远程 main 分支
Write-Host "`n🗑️  删除远程 main 分支..." -ForegroundColor Cyan
git push origin --delete main
if ($LASTEXITCODE -eq 0) {
    Write-Host "✓ 远程 main 分支已删除" -ForegroundColor Green
} else {
    Write-Host "⚠ 删除远程 main 失败，请检查：" -ForegroundColor Yellow
    Write-Host "   1. main 是否是 GitHub 默认分支（需要在 GitHub 设置中修改）" -ForegroundColor Yellow
    Write-Host "   2. main 是否有保护规则（需要先移除）" -ForegroundColor Yellow
}

# 4. 显示最终分支状态
Write-Host "`n📊 当前分支状态:" -ForegroundColor Cyan
git branch -a

Write-Host "`n✅ 清理完成！" -ForegroundColor Green
Write-Host "`n保留的分支:" -ForegroundColor Green
Write-Host "  ✓ master (生产分支)" -ForegroundColor White
Write-Host "  ✓ develop (开发分支)" -ForegroundColor White
Write-Host "`n已删除:" -ForegroundColor Red
Write-Host "  ✗ main" -ForegroundColor Gray

