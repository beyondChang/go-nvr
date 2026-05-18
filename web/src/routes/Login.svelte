<script lang="ts">
  import { login, changePassword, isAuthenticated } from '$lib/api';
  import ThemeToggle from '../components/ThemeToggle.svelte';
  import LanguageSwitcher from '../components/LanguageSwitcher.svelte';
  import { t } from '$lib/i18n';
  import { Eye, EyeOff } from 'lucide-svelte';
  import type { LoginResponse } from '$lib/api';

  let username = '';
  let password = '';
  let showPassword = false;
  let error = '';
  let loginErrors = $state({ username: '', password: '' });
  let loading = false;

  // Password change state (for first login)
  let changingPassword = $state(false);
  let newPassword = $state('');
  let confirmPassword = $state('');
  let showNewPassword = $state(false);
  let showConfirmPassword = $state(false);
  let passwordChangeError = $state('');
  let passwordChanging = $state(false);

  // Redirect if already logged in
  if (isAuthenticated()) {
    window.location.hash = '#/recordings';
  }

  function validateUsername() {
    if (!username.trim()) {
      loginErrors.username = t('login.usernameRequired');
    } else {
      loginErrors.username = '';
    }
  }

  function validatePassword() {
    if (!password) {
      loginErrors.password = t('login.passwordRequired');
    } else {
      loginErrors.password = '';
    }
  }

  function onUsernameInput() { if (loginErrors.username) loginErrors.username = ''; }
  function onPasswordInput() { if (loginErrors.password) loginErrors.password = ''; }

  async function handleSubmit() {
    validateUsername();
    validatePassword();
    if (loginErrors.username || loginErrors.password) return;

    error = '';
    loading = true;

    try {
      const result: LoginResponse = await login(username, password);
      if (result.force_password_change) {
        changingPassword = true;
      } else {
        window.location.hash = '#/recordings';
      }
    } catch (e) {
      error = e instanceof Error ? e.message : t('login.failed');
      // Clear stored credentials on failure
      import('$lib/api').then(m => m.clearCredentials());
    } finally {
      loading = false;
    }
  }

  async function handleChangePassword() {
    passwordChangeError = '';

    if (!newPassword) {
      passwordChangeError = t('login.passwordRequired');
      return;
    }
    if (newPassword.length < 6) {
      passwordChangeError = t('login.passwordTooShort');
      return;
    }
    if (newPassword !== confirmPassword) {
      passwordChangeError = t('login.passwordsDoNotMatch');
      return;
    }

    passwordChanging = true;
    try {
      await changePassword(password, newPassword);
      // Update stored credentials with new password
      import('$lib/api').then(m => m.storeCredentials(username, newPassword));
      window.location.hash = '#/recordings';
    } catch (e) {
      passwordChangeError = e instanceof Error ? e.message : t('login.failed');
    } finally {
      passwordChanging = false;
    }
  }

  function handleKeydown(e: KeyboardEvent) {
    if (e.key === 'Enter' && !changingPassword) {
      handleSubmit();
    }
  }
</script>

<div class="min-h-screen flex items-center justify-center th-bg-primary px-4">
  <div class="fixed top-4 right-4 flex items-center gap-2 z-50">
    <ThemeToggle />
    <LanguageSwitcher />
  </div>

  <div class="card w-full max-w-md p-10 border th-border shadow-2xl">
    <div class="text-center mb-10">
      <img src="/logo-full.png" alt="Logo" class="w-[60px] h-[60px] mx-auto mb-4 rounded-xl" />
      <h1 class="text-3xl font-bold bg-gradient-to-r from-violet-400 to-blue-400 bg-clip-text text-transparent mb-3">{t('login.title')}</h1>
      <p class="th-text-tertiary text-sm">{t('login.subtitle')}</p>
    </div>

    {#if error}
      <div class="mb-6 p-3 bg-[rgba(239,68,68,0.3)] border th-border-danger rounded-lg th-color-danger text-sm">
        {error}
      </div>
    {/if}

    {#if changingPassword}
      <!-- Force password change form -->
      <div class="mb-6 p-4 bg-[rgba(245,158,11,0.2)] border border-yellow-500/50 rounded-lg">
        <p class="text-yellow-300 text-sm font-medium">首次登录 - 请修改密码</p>
      </div>

      {#if passwordChangeError}
        <div class="mb-6 p-3 bg-[rgba(239,68,68,0.3)] border th-border-danger rounded-lg th-color-danger text-sm">
          {passwordChangeError}
        </div>
      {/if}

      <form on:submit|preventDefault={handleChangePassword} class="space-y-6">
        <div>
          <label for="newPassword" class="input-label">新密码</label>
          <div class="relative">
            <input
              id="newPassword"
              type={showNewPassword ? 'text' : 'password'}
              class="input pr-10"
              bind:value={newPassword}
              placeholder="至少 6 个字符"
              disabled={passwordChanging}
              autocomplete="new-password"
            />
            <button
              type="button"
              class="absolute right-2 top-1/2 -translate-y-1/2 th-text-tertiary hover:th-text-primary transition-colors"
              on:click={() => showNewPassword = !showNewPassword}
              aria-label={showNewPassword ? '隐藏密码' : '显示密码'}
            >
              {#if showNewPassword}
                <EyeOff class="w-4 h-4" />
              {:else}
                <Eye class="w-4 h-4" />
              {/if}
            </button>
          </div>
        </div>

        <div>
          <label for="confirmPassword" class="input-label">确认新密码</label>
          <div class="relative">
            <input
              id="confirmPassword"
              type={showConfirmPassword ? 'text' : 'password'}
              class="input pr-10"
              bind:value={confirmPassword}
              placeholder="再次输入新密码"
              disabled={passwordChanging}
              autocomplete="new-password"
            />
            <button
              type="button"
              class="absolute right-2 top-1/2 -translate-y-1/2 th-text-tertiary hover:th-text-primary transition-colors"
              on:click={() => showConfirmPassword = !showConfirmPassword}
              aria-label={showConfirmPassword ? '隐藏密码' : '显示密码'}
            >
              {#if showConfirmPassword}
                <EyeOff class="w-4 h-4" />
              {:else}
                <Eye class="w-4 h-4" />
              {/if}
            </button>
          </div>
        </div>

        <button type="submit" class="btn btn-primary w-full" disabled={passwordChanging}>
          {#if passwordChanging}
            <span class="spinner mr-2"></span>
            修改中...
          {:else}
            修改密码
          {/if}
        </button>
      </form>
    {:else}
      <!-- Login form -->
      <form on:submit|preventDefault={handleSubmit} class="space-y-6">
        <div>
          <label for="username" class="input-label">{t('login.username')}</label>
          <input
            id="username"
            type="text"
            class="input {loginErrors.username ? 'border-red-500' : ''}"
            bind:value={username}
            placeholder={t('login.usernamePlaceholder')}
            disabled={loading}
            on:keydown={handleKeydown}
            on:blur={validateUsername}
            on:input={onUsernameInput}
            autocomplete="username"
          />
          {#if loginErrors.username}
            <p class="th-color-danger text-xs mt-1">{loginErrors.username}</p>
          {/if}
        </div>

        <div>
          <label for="password" class="input-label">{t('login.password')}</label>
          <div class="relative">
            <input
              id="password"
              type={showPassword ? 'text' : 'password'}
              class="input pr-10 {loginErrors.password ? 'border-red-500' : ''}"
              bind:value={password}
              placeholder={t('login.passwordPlaceholder')}
              disabled={loading}
              on:keydown={handleKeydown}
              on:blur={validatePassword}
              on:input={onPasswordInput}
              autocomplete="current-password"
            />
            <button
              type="button"
              class="absolute right-2 top-1/2 -translate-y-1/2 th-text-tertiary hover:th-text-primary transition-colors"
              on:click={() => showPassword = !showPassword}
              aria-label={showPassword ? t('common.hidePassword') : t('common.showPassword')}
            >
              {#if showPassword}
                <EyeOff class="w-4 h-4" />
              {:else}
                <Eye class="w-4 h-4" />
              {/if}
            </button>
          </div>
          {#if loginErrors.password}
            <p class="th-color-danger text-xs mt-1">{loginErrors.password}</p>
          {/if}
        </div>

        <button type="submit" class="btn btn-primary w-full" disabled={loading}>
          {#if loading}
            <span class="spinner mr-2"></span>
            {t('login.signingIn')}
          {:else}
            {t('login.signIn')}
          {/if}
        </button>
      </form>
    {/if}

    <div class="mt-8 text-center text-sm th-text-tertiary">
      <p class="border-t th-border pt-6">{t('login.secureNote')}</p>
    </div>
  </div>
</div>
