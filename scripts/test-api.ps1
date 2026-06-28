param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Email = "testvang@gmail.com",
    [string]$Password = "123456"
)

$ErrorActionPreference = "Stop"
$script:PassCount = 0
$script:FailCount = 0

function Write-Pass {
    param([string]$Name)
    $script:PassCount++
    Write-Host "[PASS] $Name" -ForegroundColor Green
}

function Write-Fail {
    param(
        [string]$Name,
        [object]$ErrorData
    )
    $script:FailCount++
    Write-Host "[FAIL] $Name" -ForegroundColor Red
    if ($null -ne $ErrorData) {
        $ErrorData | ConvertTo-Json -Depth 20 | Write-Host
    }
}

function Invoke-JsonApi {
    param(
        [string]$Name,
        [string]$Method,
        [string]$Path,
        [object]$Body = $null,
        [string]$Token = $null
    )

    $headers = @{}
    if (-not [string]::IsNullOrWhiteSpace($Token)) {
        $headers["Authorization"] = "Bearer $Token"
    }

    $uri = "$BaseUrl$Path"
    try {
        $params = @{
            Method = $Method
            Uri = $uri
            Headers = $headers
        }

        if ($null -ne $Body) {
            $params["ContentType"] = "application/json; charset=utf-8"
            $params["Body"] = ($Body | ConvertTo-Json -Depth 20)
        }

        $response = Invoke-RestMethod @params
        if ($null -ne $response.status -and $response.status -ne "OK") {
            Write-Fail $Name $response
            return $null
        }

        Write-Pass $Name
        return $response
    } catch {
        $errorResponse = $_.Exception.Message
        if ($_.Exception.Response) {
            try {
                $stream = $_.Exception.Response.GetResponseStream()
                $reader = New-Object System.IO.StreamReader($stream)
                $errorResponse = $reader.ReadToEnd()
            } catch {
                $errorResponse = $_.Exception.Message
            }
        }
        Write-Fail $Name $errorResponse
        return $null
    }
}

Write-Host "Testing Flashcards API at $BaseUrl" -ForegroundColor Cyan

$apiRoot = Invoke-JsonApi -Name "GET /api" -Method "GET" -Path "/api"
$health = Invoke-JsonApi -Name "GET /api/health" -Method "GET" -Path "/api/health"
$dbTest = Invoke-JsonApi -Name "GET /api/db-test" -Method "GET" -Path "/api/db-test"
$publicSets = Invoke-JsonApi -Name "GET /api/sets/public" -Method "GET" -Path "/api/sets/public"
$categories = Invoke-JsonApi -Name "GET /api/categories" -Method "GET" -Path "/api/categories"

$loginBody = @{
    email = $Email
    password = $Password
}
$login = Invoke-JsonApi -Name "POST /api/auth/login" -Method "POST" -Path "/api/auth/login" -Body $loginBody
if ($null -eq $login -or [string]::IsNullOrWhiteSpace($login.token)) {
    Write-Host "Cannot continue because login did not return a token." -ForegroundColor Red
    exit 1
}
$token = $login.token

$mySets = Invoke-JsonApi -Name "GET /api/sets/my" -Method "GET" -Path "/api/sets/my" -Token $token

$createSetBody = @{
    title = "Auto test set Kotlin"
    description = "Set duoc tao tu dong khi Codex test API"
    categoryId = 1
    isPublic = $true
}
$createdSet = Invoke-JsonApi -Name "POST /api/sets" -Method "POST" -Path "/api/sets" -Body $createSetBody -Token $token
if ($null -eq $createdSet -or $null -eq $createdSet.data -or $null -eq $createdSet.data.setId) {
    Write-Host "Cannot continue because set creation did not return setId." -ForegroundColor Red
    exit 1
}
$setId = [int64]$createdSet.data.setId

$createCardBody = @{
    setId = $setId
    term = "hello"
    definition = "xin chao"
    example = "Hello, how are you?"
    exampleMeaning = "Xin chao, ban khoe khong?"
    pronunciation = "/huh-loh/"
    partOfSpeech = "interjection"
    imageUrl = ""
}
$createdCard = Invoke-JsonApi -Name "POST /api/cards" -Method "POST" -Path "/api/cards" -Body $createCardBody -Token $token
if ($null -eq $createdCard -or $null -eq $createdCard.data -or $null -eq $createdCard.data.cardId) {
    Write-Host "Cannot continue because card creation did not return cardId." -ForegroundColor Red
    exit 1
}
$cardId = [int64]$createdCard.data.cardId

$cards = Invoke-JsonApi -Name "GET /api/sets/detail/$setId/cards" -Method "GET" -Path "/api/sets/detail/$setId/cards"

$updateCardBody = @{
    term = "hello updated"
    definition = "xin chao cap nhat"
    example = "Hello again."
    exampleMeaning = "Xin chao lan nua."
    pronunciation = "/huh-loh/"
    partOfSpeech = "interjection"
    imageUrl = ""
}
$updatedCard = Invoke-JsonApi -Name "PUT /api/cards/$cardId" -Method "PUT" -Path "/api/cards/$cardId" -Body $updateCardBody -Token $token

$deletedCard = Invoke-JsonApi -Name "DELETE /api/cards/$cardId" -Method "DELETE" -Path "/api/cards/$cardId" -Token $token

$updateSetBody = @{
    title = "Auto test set Kotlin updated"
    description = "Set da duoc cap nhat khi Codex test API"
    categoryId = 1
    isPublic = $true
}
$updatedSet = Invoke-JsonApi -Name "PUT /api/sets/$setId" -Method "PUT" -Path "/api/sets/$setId" -Body $updateSetBody -Token $token

$mySetsAfterUpdate = Invoke-JsonApi -Name "GET /api/sets/my after update" -Method "GET" -Path "/api/sets/my" -Token $token

$adminDashboard = Invoke-JsonApi -Name "GET /api/admin/dashboard" -Method "GET" -Path "/api/admin/dashboard" -Token $token
$adminUsers = Invoke-JsonApi -Name "GET /api/admin/users" -Method "GET" -Path "/api/admin/users" -Token $token
$adminSets = Invoke-JsonApi -Name "GET /api/admin/sets" -Method "GET" -Path "/api/admin/sets" -Token $token
$adminPendingSets = Invoke-JsonApi -Name "GET /api/admin/pending-sets" -Method "GET" -Path "/api/admin/pending-sets" -Token $token
$adminReports = Invoke-JsonApi -Name "GET /api/admin/reports" -Method "GET" -Path "/api/admin/reports" -Token $token
$adminCardReports = Invoke-JsonApi -Name "GET /api/admin/card-reports" -Method "GET" -Path "/api/admin/card-reports" -Token $token
$adminAnalytics = Invoke-JsonApi -Name "GET /api/admin/analytics" -Method "GET" -Path "/api/admin/analytics" -Token $token
$adminApproveSet = Invoke-JsonApi -Name "PUT /api/admin/sets/$setId/approval" -Method "PUT" -Path "/api/admin/sets/$setId/approval" -Body @{
    status = "Approved"
    reason = ""
} -Token $token
$adminHideSet = Invoke-JsonApi -Name "PUT /api/admin/sets/$setId/hide true" -Method "PUT" -Path "/api/admin/sets/$setId/hide" -Body @{
    isHidden = $true
} -Token $token
$adminShowSet = Invoke-JsonApi -Name "PUT /api/admin/sets/$setId/hide false" -Method "PUT" -Path "/api/admin/sets/$setId/hide" -Body @{
    isHidden = $false
} -Token $token
if ($null -ne $login.user -and $null -ne $login.user.userId) {
    $adminUserStatus = Invoke-JsonApi -Name "PUT /api/admin/users/$($login.user.userId)/status" -Method "PUT" -Path "/api/admin/users/$($login.user.userId)/status" -Body @{
        status = $login.user.status
    } -Token $token
    $adminUserRole = Invoke-JsonApi -Name "PUT /api/admin/users/$($login.user.userId)/role" -Method "PUT" -Path "/api/admin/users/$($login.user.userId)/role" -Body @{
        role = $login.user.role
    } -Token $token
}
$progress = Invoke-JsonApi -Name "GET /api/progress/my" -Method "GET" -Path "/api/progress/my" -Token $token
$saveProgress = Invoke-JsonApi -Name "POST /api/progress" -Method "POST" -Path "/api/progress" -Body @{
    setId = $setId
    totalCards = 1
    rememberedCards = 1
    notRememberedCards = 0
} -Token $token

$gameMatching = Invoke-JsonApi -Name "GET /api/games/matching/$setId" -Method "GET" -Path "/api/games/matching/$setId" -Token $token
$gamesHistory = Invoke-JsonApi -Name "GET /api/games/history" -Method "GET" -Path "/api/games/history" -Token $token
$quizFallback = Invoke-JsonApi -Name "GET /api/quiz/$setId" -Method "GET" -Path "/api/quiz/$setId" -Token $token
$quizHistory = Invoke-JsonApi -Name "GET /api/quiz/history" -Method "GET" -Path "/api/quiz/history" -Token $token
$groups = Invoke-JsonApi -Name "GET /api/groups/my" -Method "GET" -Path "/api/groups/my" -Token $token
$missions = Invoke-JsonApi -Name "GET /api/missions/my" -Method "GET" -Path "/api/missions/my" -Token $token
$badges = Invoke-JsonApi -Name "GET /api/badges/my" -Method "GET" -Path "/api/badges/my" -Token $token
$reportsSummary = Invoke-JsonApi -Name "GET /api/reports/summary" -Method "GET" -Path "/api/reports/summary" -Token $token
$smartDashboard = Invoke-JsonApi -Name "GET /api/smart-learning/dashboard" -Method "GET" -Path "/api/smart-learning/dashboard" -Token $token
$rankings = Invoke-JsonApi -Name "GET /api/rankings" -Method "GET" -Path "/api/rankings" -Token $token
$notificationsCount = Invoke-JsonApi -Name "GET /api/notifications/unread-count" -Method "GET" -Path "/api/notifications/unread-count" -Token $token
$favorites = Invoke-JsonApi -Name "GET /api/favorites" -Method "GET" -Path "/api/favorites" -Token $token

Write-Host ""
Write-Host "Summary: $script:PassCount passed, $script:FailCount failed" -ForegroundColor Cyan
Write-Host "Created set remains in database for inspection. setId=$setId" -ForegroundColor Yellow

if ($script:FailCount -gt 0) {
    exit 1
}
