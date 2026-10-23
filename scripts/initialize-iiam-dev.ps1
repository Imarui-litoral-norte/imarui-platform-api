param(
    [Parameter(Mandatory)][string]$AdminConfiguration,
    [string]$RuntimeDirectory = (Join-Path $PSScriptRoot '../../.runtime/iiam'),
    [string]$PostgresBin = 'C:/Program Files/PostgreSQL/18/bin',
    [string]$JavaHome = 'C:/Users/lucas.l/.jdks/ms-17.0.18'
)
$ErrorActionPreference = 'Stop'
$runtime = [IO.Path]::GetFullPath($RuntimeDirectory)
New-Item -ItemType Directory -Force $runtime | Out-Null
$settings = Get-Content -LiteralPath $AdminConfiguration -Raw | ConvertFrom-StringData
$previousPassword = $env:PGPASSWORD
function Invoke-Database([string]$Sql) {
    $result = $Sql | & "$PostgresBin/psql.exe" -X -w -v ON_ERROR_STOP=1 -h $settings.PGHOST -p $settings.PGPORT -U $settings.PGUSER -d $settings.PGDATABASE -At
    if ($LASTEXITCODE -ne 0) { throw 'IIAM database provisioning failed' }
    return $result
}
$environmentFile = Join-Path $runtime 'iiam.properties'
try {
    $env:PGPASSWORD = $settings.PGPASSWORD
    if (-not (Test-Path $environmentFile)) {
        if ((Invoke-Database "select count(*) from pg_roles where rolname='iiam_dev'") -ne '0') {
            throw 'iiam_dev already exists; provide its existing configuration instead of resetting credentials'
        }
        if ((Invoke-Database "select count(*) from pg_database where datname='iiam_dev'") -ne '0') {
            throw 'iiam_dev database already exists; investigate ownership before provisioning'
        }
        $password = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
        $privateKey = Join-Path $runtime 'signing-private.pem'
        $publicKey = Join-Path $runtime 'signing-public.pem'
        $rsa = [Security.Cryptography.RSA]::Create(3072)
        try {
            [IO.File]::WriteAllText($privateKey, $rsa.ExportPkcs8PrivateKeyPem())
            [IO.File]::WriteAllText($publicKey, $rsa.ExportSubjectPublicKeyInfoPem())
        } finally { $rsa.Dispose() }
        $storePassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
        $storePasswordFile = Join-Path $runtime 'tls-store-password.txt'
        [IO.File]::WriteAllText($storePasswordFile, $storePassword)
        $keyStore = Join-Path $runtime 'localhost.p12'
        & "$JavaHome/bin/keytool.exe" -genkeypair -alias iiam-local -keyalg RSA -keysize 3072 -validity 365 -dname 'CN=localhost' -ext 'SAN=dns:localhost,ip:127.0.0.1' -storetype PKCS12 -keystore $keyStore -storepass:file $storePasswordFile
        if ($LASTEXITCODE -ne 0) { throw 'TLS key generation failed' }
        & "$JavaHome/bin/keytool.exe" -exportcert -rfc -alias iiam-local -keystore $keyStore -storepass:file $storePasswordFile -file (Join-Path $runtime 'localhost.crt')
        if ($LASTEXITCODE -ne 0) { throw 'TLS certificate export failed' }
        $configuration = @{
            DB_URL = "jdbc:postgresql://127.0.0.1:$($settings.PGPORT)/iiam_dev"
            DB_USERNAME = 'iiam_dev'; DB_PASSWORD = $password
            JWT_PRIVATE_KEY_FILE = $privateKey.Replace('\','/')
            SERVER_ADDRESS = '127.0.0.1'; SERVER_PORT = '4105'
            SERVER_SSL_ENABLED = 'true'; SERVER_SSL_KEY_STORE = $keyStore.Replace('\','/')
            SERVER_SSL_KEY_STORE_TYPE = 'PKCS12'; SERVER_SSL_KEY_STORE_PASSWORD = $storePassword
            AUTH_REQUIRE_HTTPS = 'true'
            AUTH_ALLOWED_ORIGINS = 'https://localhost:8443,https://localhost:8444'
            APP_BASE_URL = 'https://localhost:8443/api/iam'
            JWT_ISSUER = 'imarui-iam-service'; JWT_AUDIENCE = 'imarui-iam'
        }
        # Preserve generated credentials before CREATE ROLE, so partial failure is recoverable.
        [IO.File]::WriteAllLines($environmentFile, @($configuration.GetEnumerator() | Sort-Object Key | ForEach-Object {"$($_.Key)=$($_.Value)"}))
        Invoke-Database "CREATE ROLE iiam_dev LOGIN PASSWORD '$password' NOSUPERUSER NOCREATEDB NOCREATEROLE;" | Out-Null
        Invoke-Database 'CREATE DATABASE iiam_dev OWNER iiam_dev TEMPLATE template0 ENCODING ''UTF8'';' | Out-Null
        Invoke-Database 'REVOKE ALL ON DATABASE iiam_dev FROM PUBLIC;' | Out-Null
    }
    if ((Invoke-Database "select count(*) from pg_roles where rolname='iiam_dev'") -ne '1' -or
        (Invoke-Database "select count(*) from pg_database where datname='iiam_dev' and pg_get_userbyid(datdba)='iiam_dev'") -ne '1') {
        throw 'Incomplete provisioning: inspect the saved configuration and database ownership before retrying'
    }
    Write-Output 'IIAM private DEV configuration is ready. No legacy users imported.'
} finally { $env:PGPASSWORD = $previousPassword }
