<script lang="ts">
  import { onMount, onDestroy } from 'svelte';
  import { getSettings, updateSettings, getMergeSettings, updateMergeSettings } from '$lib/api';
  import { getItemsPerPage, setItemsPerPage, getAutoRefresh, setAutoRefresh } from '../lib/preferences';
  import type { SettingsConfig } from '$lib/api';
  import { t } from '$lib/i18n';
  import { AlertCircle, X, LogOut, ChevronDown, UserPlus, Trash2 } from 'lucide-svelte';
  import { showToast } from '$lib/toast';
  import { logout, getCredentials, storeCredentials } from '$lib/api';
  import { isAdmin } from '$lib/api';
  import { listUsers, createUser, updateUser, deleteUser } from '$lib/api';
  import type { User, CreateUserRequest, UpdateUserRequest } from '$lib/api';

  let { onclose }: { onclose?: () => void } = $props();

  // Prevent body scroll when drawer is open
  onMount(() => {
    document.body.style.overflow = 'hidden';
  });
  onDestroy(() => {
    document.body.style.overflow = '';
  });

  let settings = $state<SettingsConfig | null>(null);
  let loading = $state(true);
  let error = $state('');
  let saving = $state(false);

// Form state
let retentionDays = $state(30);
let diskThresholdPercent = $state(90);
let checkInterval = $state('1h');
let itemsPerPage = $state(getItemsPerPage());
  let autoRefresh = $state(getAutoRefresh());
let webdavEnabled = $state(false);
let webdavPathPrefix = $state('/dav');
let webdavReadWrite = $state(false);

// Merge settings state
let mergeEnabled = $state(true);
let mergeCheckInterval = $state('1h');
let mergeWindowSize = $state('1h');
let mergeMinSegments = $state(3);
let mergeMinSegmentAge = $state('10m');
let mergeBatchLimit = $state(100);

// User management state
let users = $state<User[]>([]);
let usersLoading = $state(false);
let showAddUserForm = $state(false);
let editingUserId = $state<string | null>(null);
let userForm = $state({ username: '', password: '', role: 'viewer' });
let userSaving = $state(false);
let deletingUser = $state<User | null>(null);

  // Original values for change tracking
  let originalRetentionDays = $state(30);

  // Validation
  let validationErrors = $state<Record<string, string>>({});


  // Confirmation dialog
  let showConfirmDialog = $state(false);

  // Collapse state for each section — all collapsed by default
  let collapsedSections = $state<Record<string, boolean>>({
    cleanup: true,
    webdav: true,
    merge: true,
    frontend: true,
    users: true,
  });

  function toggleSection(section: string) {
    collapsedSections[section] = !collapsedSections[section];
  }
  function validateField(field: string, value: string) {
    const val = parseInt(value);
    if (field === 'retention_days') {
      if (isNaN(val) || val < 0) {
        validationErrors['retention_days'] = t('settings.invalidRetentionDays');
      } else {
        delete validationErrors['retention_days'];
      }
    } else if (field === 'disk_threshold') {
      if (isNaN(val) || val < 0 || val > 100) {
        validationErrors['disk_threshold'] = t('settings.invalidDiskThreshold');
      } else {
        delete validationErrors['disk_threshold'];
      }
    }
  }

  function validate(): boolean {
    validationErrors = {};

    if (retentionDays < 1) {
      validationErrors['retention_days'] = t('settings.validationRetention');
    }

    if (diskThresholdPercent < 0 || diskThresholdPercent > 100) {
      validationErrors['disk_threshold'] = t('settings.validationThreshold');
    }

    return Object.keys(validationErrors).length === 0;
  }

  async function loadSettings() {
    loading = true;
    error = '';

    try {
      settings = await getSettings();
retentionDays = settings.cleanup.retention_days;
diskThresholdPercent = settings.cleanup.disk_threshold_percent;
      checkInterval = settings.cleanup.check_interval;
      originalRetentionDays = settings.cleanup.retention_days;
      webdavEnabled = settings.webdav?.enabled ?? false;
      webdavPathPrefix = settings.webdav?.path_prefix ?? '/dav';
      webdavReadWrite = settings.webdav?.read_write ?? false;

      // Load merge settings
      const mergeSettings = await getMergeSettings();
      mergeEnabled = mergeSettings.enabled ?? true;
      mergeCheckInterval = mergeSettings.check_interval ?? '1h';
      mergeWindowSize = mergeSettings.window_size ?? '1h';
      mergeMinSegments = mergeSettings.min_segments_to_merge ?? 3;
      mergeMinSegmentAge = mergeSettings.min_segment_age ?? '10m';
      mergeBatchLimit = mergeSettings.batch_limit ?? 100;
    } catch (e) {
      error = e instanceof Error ? e.message : t('common.failedLoadSettings');
    } finally {
      loading = false;
    }
  }

  async function save() {
    if (!validate()) return;

    // Check if we're reducing retention (destructive change)
    if (retentionDays < originalRetentionDays && originalRetentionDays > 0) {
      showConfirmDialog = true;
      return;
    }

    await performSave();
  }

  async function performSave() {
    saving = true;

    try {
      const payload: SettingsConfig = {
        cleanup: {
          retention_days: retentionDays,
          disk_threshold_percent: diskThresholdPercent,
          check_interval: checkInterval,
        },
        webdav: {
          enabled: webdavEnabled,
          path_prefix: webdavPathPrefix,
          read_write: webdavReadWrite,
        },
      };

      const result = await updateSettings(payload);

      // Save merge settings
      await updateMergeSettings({
        enabled: mergeEnabled,
        check_interval: mergeCheckInterval,
        window_size: mergeWindowSize,
        min_segments_to_merge: mergeMinSegments,
        min_segment_age: mergeMinSegmentAge,
        batch_limit: mergeBatchLimit,
      });

      settings = await getSettings();
      originalRetentionDays = settings.cleanup.retention_days;
      showToast(t('settings.saved'), 'success');
    } catch (e) {
      showToast(e instanceof Error ? e.message : t('common.failedSaveSettings'), 'error');
    } finally {
      saving = false;
    }
  }

  function confirmSave() {
    showConfirmDialog = false;
    performSave();
  }

  function cancelSave() {
    showConfirmDialog = false;
  }

  function handleItemsPerPageChange() {
    setItemsPerPage(itemsPerPage);
  }

  function handleAutoRefreshChange(event: Event) {
    const select = event.target as HTMLSelectElement;
    setAutoRefresh(select.value);
  }

  // --- User management ---
  async function loadUsers() {
    usersLoading = true;
    try {
      users = await listUsers();
    } catch (e) {
      showToast(e instanceof Error ? e.message : '加载用户失败', 'error');
    } finally {
      usersLoading = false;
    }
  }

  function openAddUser() {
    userForm = { username: '', password: '', role: 'viewer' };
    editingUserId = null;
    showAddUserForm = true;
  }

  function openEditUser(user: User) {
    userForm = { username: user.username, password: '', role: user.role };
    editingUserId = user.id;
    showAddUserForm = true;
  }

  function cancelUserForm() {
    showAddUserForm = false;
    editingUserId = null;
  }

  async function saveUser() {
    if (!userForm.username) {
      showToast('用户名不能为空', 'error');
      return;
    }
    if (!editingUserId && !userForm.password) {
      showToast('密码不能为空', 'error');
      return;
    }
    if (userForm.password && userForm.password.length < 6) {
      showToast('密码至少需要 6 个字符', 'error');
      return;
    }

    userSaving = true;
    try {
      if (editingUserId) {
        const data: UpdateUserRequest = {
          username: userForm.username,
          role: userForm.role,
        };
        if (userForm.password) {
          data.password = userForm.password;
        }
        await updateUser(editingUserId, data);
        // If the current user changed their own password, update stored credentials
        const currentCreds = getCredentials();
        if (currentCreds && currentCreds.username === userForm.username && userForm.password) {
          storeCredentials(currentCreds.username, userForm.password);
        }
        showToast('用户已更新', 'success');
      } else {
        const data: CreateUserRequest = {
          username: userForm.username,
          password: userForm.password,
          role: userForm.role,
        };
        await createUser(data);
        showToast('用户已创建', 'success');
      }
      showAddUserForm = false;
      await loadUsers();
    } catch (e) {
      showToast(e instanceof Error ? e.message : '操作失败', 'error');
    } finally {
      userSaving = false;
    }
  }

  async function confirmDeleteUser(user: User) {
    deletingUser = user;
  }

  async function handleDeleteUserConfirm() {
    if (!deletingUser) return;
    try {
      await deleteUser(deletingUser.id);
      showToast('用户已删除', 'success');
      deletingUser = null;
      await loadUsers();
    } catch (e) {
      showToast(e instanceof Error ? e.message : '删除失败', 'error');
    }
  }

  function handleKeydown(e: KeyboardEvent) {
    if (e.key === 'Escape' && onclose) onclose();
  }

  onMount(() => {
    loadSettings();
    loadUsers();
    window.addEventListener('keydown', handleKeydown);
  });

  onDestroy(() => {
    window.removeEventListener('keydown', handleKeydown);
  });
</script>

<!-- Backdrop -->
<div class="fixed inset-0 bg-black/70 z-[2000]" onclick={onclose}></div>

<!-- Drawer -->
<div class="fixed top-0 right-0 h-full w-full max-w-2xl z-[2001] shadow-2xl shadow-black/30 border-l th-border settings-drawer th-bg-tertiary flex flex-col">
  <!-- Drawer header -->
  <div class="sticky top-0 z-10 flex items-center justify-between p-4 border-b th-border th-bg-tertiary flex-shrink-0">
    <h2 class="text-xl font-bold th-text-primary">{t('settings.title')}</h2>
    {#if isAdmin()}
    <button
      onclick={save}
      class="btn btn-primary btn-sm"
      disabled={saving}
    >
      {#if saving}
        <span class="spinner mr-1"></span>
        {t('settings.saving')}
      {:else}
        {t('settings.save')}
      {/if}
    </button>
    {/if}
  </div>

  <!-- Scrollable content area -->
  <div class="flex-1 overflow-y-auto p-6">
    {#if !isAdmin()}
      <div class="flex items-center justify-center min-h-[400px]">
        <div class="text-center">
          <AlertCircle size={48} class="mx-auto mb-4 th-color-danger" />
          <h3 class="text-lg font-medium th-text-primary mb-2">{t('common.noPermission')}</h3>
          <p class="th-text-secondary">{t('common.noPermissionDesc')}</p>
        </div>
      </div>
    {:else}
    <!-- Error message -->
    {#if error}
      <div class="card border th-border-danger p-8 text-center">
        <div class="flex justify-center mb-4 th-color-danger">
          <AlertCircle size={48} />
        </div>
        <h3 class="text-lg font-medium th-text-primary mb-2">{t('common.error')}</h3>
        <p class="th-text-secondary mb-4">{error}</p>
        <button onclick={loadSettings} class="btn btn-primary btn-sm">{t('common.retry')}</button>
      </div>
    {/if}

    <!-- Loading state -->
    {#if loading}
      <div class="card border th-border">
        <div class="p-6 space-y-4">
          <div class="h-6 w-40 th-bg-tertiary rounded animate-pulse"></div>
          <div class="h-4 w-64 th-bg-tertiary rounded animate-pulse"></div>
          <div class="grid grid-cols-1 md:grid-cols-3 gap-6">
            <div class="space-y-2">
              <div class="h-4 w-24 th-bg-tertiary rounded animate-pulse"></div>
              <div class="h-10 th-bg-tertiary rounded animate-pulse"></div>
            </div>
            <div class="space-y-2">
              <div class="h-4 w-32 th-bg-tertiary rounded animate-pulse"></div>
              <div class="h-3 w-full th-bg-tertiary rounded animate-pulse"></div>
              <div class="h-10 th-bg-tertiary rounded animate-pulse"></div>
            </div>
            <div class="space-y-2">
              <div class="h-4 w-28 th-bg-tertiary rounded animate-pulse"></div>
              <div class="h-10 th-bg-tertiary rounded animate-pulse"></div>
            </div>
          </div>
          <div class="flex items-center gap-4 pt-2">
            <div class="h-10 w-28 th-bg-tertiary rounded animate-pulse"></div>
          </div>
        </div>
      </div>
    {:else}
      <div class="space-y-5">
        <!-- Cleanup Policy -->
        <div class="settings-group {collapsedSections.cleanup ? '' : ''}">
          <button
            onclick={() => toggleSection('cleanup')}
            class="settings-group-header"
          >
            <span class="settings-group-title">{t('settings.cleanup')}</span>
            <span class="settings-group-desc">{t('settings.cleanupDesc')}</span>
            <ChevronDown
              size={20}
              class="settings-group-chevron {collapsedSections.cleanup ? 'collapsed' : ''}"
            />
          </button>

          <div class="settings-group-body {collapsedSections.cleanup ? 'hidden' : ''}">
            <div class="grid grid-cols-1 md:grid-cols-3 gap-6">
              <!-- Retention Days -->
              <div>
                <label for="retention" class="input-label">{t('settings.retentionDays')}</label>
                <input
                  id="retention"
                  type="number"
                  class="input {validationErrors['retention_days'] ? 'border-red-500' : ''}"
                  bind:value={retentionDays}
                  min="1"
                  onblur={() => validateField('retention_days', String(retentionDays))}
                  oninput={() => { if (validationErrors['retention_days']) delete validationErrors['retention_days']; }}
                />
                {#if validationErrors['retention_days']}
                  <p class="th-color-danger text-xs mt-1" aria-live="polite">{validationErrors['retention_days']}</p>
                {/if}
              </div>

              <!-- Disk Threshold -->
              <div>
                <label for="threshold" class="input-label">{t('settings.diskThreshold', { percent: String(diskThresholdPercent) })}</label>
                <input
                  id="threshold"
                  type="number"
                  class="input {validationErrors['disk_threshold'] ? 'border-red-500' : ''}"
                  bind:value={diskThresholdPercent}
                  min="0"
                  max="100"
                  onblur={() => validateField('disk_threshold', String(diskThresholdPercent))}
                  oninput={() => { if (validationErrors['disk_threshold']) delete validationErrors['disk_threshold']; }}
                />
                {#if validationErrors['disk_threshold']}
                  <p class="th-color-danger text-xs mt-1" aria-live="polite">{validationErrors['disk_threshold']}</p>
                {/if}
              </div>

              <!-- Check Interval -->
              <div>
                <label for="interval" class="input-label">{t('settings.checkInterval')}</label>
                <select id="interval" class="input" bind:value={checkInterval}>
                  <option value="30m">{t('settings.every30m')}</option>
                  <option value="1h">{t('settings.every1h')}</option>
                  <option value="6h">{t('settings.every6h')}</option>
                  <option value="24h">{t('settings.every24h')}</option>
                </select>
              </div>
            </div>
          </div>
        </div>

        <!-- WebDAV Settings -->
        <div class="settings-group {collapsedSections.webdav ? '' : ''}">
          <button
            onclick={() => toggleSection('webdav')}
            class="settings-group-header"
          >
            <span class="settings-group-title">{t('settings.webdav')}</span>
            <span class="settings-group-desc">{t('settings.webdavDesc')}</span>
            <ChevronDown
              size={20}
              class="settings-group-chevron {collapsedSections.webdav ? 'collapsed' : ''}"
            />
          </button>

          <div class="settings-group-body {collapsedSections.webdav ? 'hidden' : ''}">
            <div class="grid grid-cols-1 md:grid-cols-3 gap-6">
              <!-- Enable WebDAV -->
              <div>
                <label class="input-label">{t('settings.webdavEnabled')}</label>
                <div class="flex items-center gap-3 mt-2">
                  <button
                    type="button"
                    class="relative inline-flex h-6 w-11 items-center rounded-full transition-colors focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 {webdavEnabled ? 'bg-blue-600' : 'th-bg-tertiary'}"
                    onclick={() => { webdavEnabled = !webdavEnabled; }}
                    role="switch"
                    aria-checked={webdavEnabled}
                  >
                    <span class="inline-block h-4 w-4 transform rounded-full bg-white transition-transform {webdavEnabled ? 'translate-x-6' : 'translate-x-1'}"></span>
                  </button>
                  <span class="text-sm th-text-secondary">{webdavEnabled ? t('settings.webdavEnabledOn') : t('settings.webdavEnabledOff')}</span>
                </div>
              </div>

              <!-- Path Prefix -->
              <div>
                <label for="webdavPrefix" class="input-label">{t('settings.webdavPathPrefix')}</label>
                <input
                  id="webdavPrefix"
                  type="text"
                  class="input"
                  bind:value={webdavPathPrefix}
                  placeholder="/dav"
                />
              </div>

              <!-- Read-Write Mode -->
              <div>
                <label class="input-label">{t('settings.webdavReadWrite')}</label>
                <div class="flex items-center gap-3 mt-2">
                  <button
                    type="button"
                    class="relative inline-flex h-6 w-11 items-center rounded-full transition-colors focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 {webdavReadWrite ? 'bg-blue-600' : 'th-bg-tertiary'}"
                    onclick={() => { webdavReadWrite = !webdavReadWrite; }}
                    role="switch"
                    aria-checked={webdavReadWrite}
                  >
                    <span class="inline-block h-4 w-4 transform rounded-full bg-white transition-transform {webdavReadWrite ? 'translate-x-6' : 'translate-x-1'}"></span>
                  </button>
                  <span class="text-sm th-text-secondary">{webdavReadWrite ? t('settings.webdavReadWriteOn') : t('settings.webdavReadWriteOff')}</span>
                </div>
                <p class="text-xs th-text-tertiary mt-2">{t('settings.webdavReadWriteHint')}</p>
              </div>
            </div>
          </div>
        </div>

        <!-- Merge Strategy -->
        <div class="settings-group {collapsedSections.merge ? '' : ''}">
          <button
            onclick={() => toggleSection('merge')}
            class="settings-group-header"
          >
            <span class="settings-group-title">{t('merge.title')}</span>
            <span class="settings-group-desc">{t('merge.description')}</span>
            <ChevronDown
              size={20}
              class="settings-group-chevron {collapsedSections.merge ? 'collapsed' : ''}"
            />
          </button>

          <div class="settings-group-body {collapsedSections.merge ? 'hidden' : ''}">
            <div class="grid grid-cols-1 md:grid-cols-3 gap-6">
              <!-- Enable Merge -->
              <div>
                <label class="input-label">{t('merge.enableMerge')}</label>
                <div class="flex items-center gap-3 mt-2">
                  <button
                    type="button"
                    class="relative inline-flex h-6 w-11 items-center rounded-full transition-colors focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 {mergeEnabled ? 'bg-blue-600' : 'th-bg-tertiary'}"
                    onclick={() => { mergeEnabled = !mergeEnabled; }}
                    role="switch"
                    aria-checked={mergeEnabled}
                  >
                    <span class="inline-block h-4 w-4 transform rounded-full bg-white transition-transform {mergeEnabled ? 'translate-x-6' : 'translate-x-1'}"></span>
                  </button>
                  <span class="text-sm th-text-secondary">{mergeEnabled ? t('merge.enabledState') : t('merge.disabledState')}</span>
                </div>
              </div>

              <!-- Check Interval -->
              <div>
                <label for="mergeInterval" class="input-label">{t('merge.checkInterval')}</label>
                <select id="mergeInterval" class="input" bind:value={mergeCheckInterval}>
                  <option value="30m">{t('merge.30m')}</option>
                  <option value="1h">{t('merge.1h')}</option>
                  <option value="2h">{t('merge.2h')}</option>
                  <option value="6h">{t('merge.6h')}</option>
                </select>
              </div>

              <!-- Window Size -->
              <div>
                <label for="mergeWindow" class="input-label">{t('merge.windowSize')}</label>
                <select id="mergeWindow" class="input" bind:value={mergeWindowSize}>
                  <option value="30m">{t('merge.30m')}</option>
                  <option value="1h">{t('merge.1h')}</option>
                  <option value="2h">{t('merge.2h')}</option>
                </select>
              </div>

              <!-- Min Segments -->
              <div>
                <label for="mergeMinSegs" class="input-label">{t('merge.minSegments')}</label>
                <input
                  id="mergeMinSegs"
                  type="number"
                  class="input"
                  bind:value={mergeMinSegments}
                  min="2"
                  max="50"
                />
              </div>

              <!-- Min Segment Age -->
              <div>
                <label for="mergeMinAge" class="input-label">{t('merge.minAge')}</label>
                <select id="mergeMinAge" class="input" bind:value={mergeMinSegmentAge}>
                  <option value="5m">{t('merge.5m')}</option>
                  <option value="10m">{t('merge.10m')}</option>
                  <option value="30m">{t('merge.30m')}</option>
                  <option value="1h">{t('merge.1h')}</option>
                </select>
              </div>

              <!-- Batch Limit -->
              <div>
                <label for="mergeBatch" class="input-label">{t('merge.batchLimitLabel')}</label>
                <input
                  id="mergeBatch"
                  type="number"
                  class="input"
                  bind:value={mergeBatchLimit}
                  min="10"
                  max="1000"
                />
              </div>
            </div>
          </div>
        </div>

        <!-- Frontend Preferences -->
        <div class="settings-group {collapsedSections.frontend ? '' : ''}">
          <button
            onclick={() => toggleSection('frontend')}
            class="settings-group-header"
          >
            <span class="settings-group-title">{t('settings.frontendPrefs')}</span>
            <span class="settings-group-desc">{t('settings.frontendPrefsDesc')}</span>
            <ChevronDown
              size={20}
              class="settings-group-chevron {collapsedSections.frontend ? 'collapsed' : ''}"
            />
          </button>

          <div class="settings-group-body {collapsedSections.frontend ? 'hidden' : ''}">
            <div class="grid grid-cols-1 md:grid-cols-2 gap-6">
              <!-- Items Per Page -->
              <div>
                <label for="itemsPerPage" class="input-label">{t('settings.itemsPerPage')}</label>
                <select id="itemsPerPage" class="input" bind:value={itemsPerPage} onchange={handleItemsPerPageChange}>
                  <option value={20}>20</option>
                  <option value={50}>50</option>
                  <option value={100}>100</option>
                </select>
              </div>

              <!-- Auto Refresh -->
              <div>
                <label for="autoRefresh" class="input-label">{t('settings.autoRefresh')}</label>
                <select id="autoRefresh" class="input" bind:value={autoRefresh} onchange={handleAutoRefreshChange}>
                  <option value="30s">{t('settings.every30s')}</option>
                  <option value="60s">{t('settings.every60s')}</option>
                  <option value="120s">{t('settings.every2m')}</option>
                  <option value="off">{t('settings.off')}</option>
                </select>
              </div>
            </div>
          </div>
        </div>

        <!-- User Management -->
        {#if isAdmin()}
        <div class="settings-group {collapsedSections.users ? '' : ''}">
          <button
            onclick={() => { toggleSection('users'); if (!collapsedSections.users) loadUsers(); }}
            class="settings-group-header"
          >
            <span class="settings-group-title">{t('settings.users')}</span>
            <span class="settings-group-desc">{t('settings.usersDesc')}</span>
            <ChevronDown
              size={20}
              class="settings-group-chevron {collapsedSections.users ? 'collapsed' : ''}"
            />
          </button>

          <div class="settings-group-body {collapsedSections.users ? 'hidden' : ''}">
            {#if usersLoading}
              <div class="flex items-center justify-center py-8">
                <span class="spinner mr-2"></span>
                <span class="th-text-secondary">{t('common.loading')}</span>
              </div>
            {:else}
              <div class="space-y-4">
                <!-- User list -->
                <div class="overflow-x-auto">
                  <table class="w-full text-sm">
                    <thead>
                      <tr class="th-border-b">
                        <th class="text-left py-2 px-3 th-text-secondary font-medium">{t('settings.username')}</th>
                        <th class="text-left py-2 px-3 th-text-secondary font-medium">{t('settings.role')}</th>
                        <th class="text-right py-2 px-3 th-text-secondary font-medium">{t('settings.actions')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {#each users as user}
                        <tr class="th-border-b th-border/50">
                          <td class="py-2 px-3 th-text-primary">{user.username}</td>
                          <td class="py-2 px-3">
                            <span class="inline-block px-2 py-0.5 text-xs rounded-full {user.role === 'admin' ? 'bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-300' : 'bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-400'}">
                              {user.role === 'admin' ? '管理员' : '观察者'}
                            </span>
                          </td>
                          <td class="py-2 px-3 text-right">
                            <button
                              onclick={() => openEditUser(user)}
                              class="btn btn-ghost btn-xs mr-1"
                              title={t('settings.edit')}
                            >
                              ✏️
                            </button>
                            {#if user.role !== 'admin'}
                            <button
                              onclick={() => confirmDeleteUser(user)}
                              class="btn btn-ghost btn-xs th-color-danger"
                              title={t('settings.delete')}
                            >
                              <Trash2 size={14} />
                            </button>
                            {/if}
                          </td>
                        </tr>
                      {/each}
                    </tbody>
                  </table>
                </div>

                <!-- Add user button -->
                <button onclick={openAddUser} class="btn btn-primary btn-sm">
                  <UserPlus size={16} class="mr-1" />
                  {t('settings.addUser')}
                </button>
              </div>
            {/if}

            <!-- Add/Edit user form modal -->
            {#if showAddUserForm}
              <div class="fixed inset-0 bg-black/50 flex items-center justify-center p-4 z-50">
                <div class="card max-w-md w-full p-6">
                  <div class="flex items-center justify-between mb-4">
                    <h3 class="text-lg font-semibold th-text-primary">
                      {editingUserId ? t('settings.editUser') : t('settings.addUser')}
                    </h3>
                    <button onclick={cancelUserForm} class="btn btn-ghost btn-sm p-1">
                      <X size={20} />
                    </button>
                  </div>
                  <div class="space-y-4">
                    <div>
                      <label class="input-label">{t('login.username')}</label>
                      <input
                        type="text"
                        class="input"
                        bind:value={userForm.username}
                        placeholder={t('login.usernamePlaceholder')}
                        disabled={editingUserId !== null && userForm.role === 'admin'}
                      />
                    </div>
                    <div>
                      <label class="input-label">{t('login.password')}</label>
                      <input
                        type="password"
                        class="input"
                        bind:value={userForm.password}
                        placeholder={editingUserId ? t('settings.leaveBlank') : t('login.passwordPlaceholder')}
                      />
                    </div>
                    <div>
                      <label class="input-label">{t('settings.role')}</label>
                      <select class="input" bind:value={userForm.role} disabled={editingUserId !== null && userForm.role === 'admin'}>
                        <option value="viewer">{t('settings.roleViewer')}</option>
                        <option value="admin">{t('settings.roleAdmin')}</option>
                      </select>
                    </div>
                  </div>
                  <div class="flex gap-3 justify-end mt-6">
                    <button onclick={cancelUserForm} class="btn btn-secondary">
                      {t('recordings.cancel')}
                    </button>
                    <button onclick={saveUser} class="btn btn-primary" disabled={userSaving}>
                      {#if userSaving}
                        <span class="spinner mr-1"></span>
                      {/if}
                      {editingUserId ? t('settings.save') : t('settings.create')}
                    </button>
                  </div>
                </div>
              </div>
            {/if}

            <!-- Delete user confirmation modal -->
            {#if deletingUser}
              <div class="fixed inset-0 bg-black/50 flex items-center justify-center p-4 z-50">
                <div class="card max-w-md w-full p-6">
                  <h3 class="text-lg font-semibold th-text-primary mb-4">确认删除</h3>
                  <p class="th-text-secondary mb-6">
                    确定要删除用户 "{deletingUser.username}" 吗？此操作不可撤销。
                  </p>
                  <div class="flex gap-3 justify-end">
                    <button
                      onclick={() => { deletingUser = null; }}
                      class="btn btn-secondary"
                    >
                      {t('recordings.cancel')}
                    </button>
                    <button
                      onclick={handleDeleteUserConfirm}
                      class="btn btn-danger"
                      style="background-color: #dc2626; color: white; border-color: #dc2626;"
                    >
                      确认删除
                    </button>
                  </div>
                </div>
              </div>
            {/if}
          </div>
        </div>
        {/if}

      </div>
    {/if}
    {/if}
  </div>

  <!-- Logout bar — always at bottom of drawer -->
  <div class="flex-shrink-0 border-t th-border th-bg-tertiary p-4">
    <button
      onclick={() => { logout(); onclose?.(); }}
      class="btn btn-ghost w-full flex items-center justify-center gap-2 py-3 th-color-danger hover:bg-red-50 dark:hover:bg-red-900/20 transition-colors rounded-lg"
    >
      <LogOut size={20} />
      <span>{t('nav.logout')}</span>
    </button>
  </div>
</div>

<!-- Save confirmation modal -->
{#if showConfirmDialog}
  <div class="fixed inset-0 bg-black/50 flex items-center justify-center p-4 z-50">
    <div class="card max-w-md w-full p-6">
      <h3 class="text-lg font-semibold th-text-primary mb-4">{t('settings.confirmSaveTitle')}</h3>
      <p class="th-text-secondary mb-6">
        {t('settings.confirmSaveMessage')}
      </p>
      <div class="flex gap-3 justify-end">
        <button
          onclick={cancelSave}
          class="btn btn-secondary"
        >
          {t('recordings.cancel')}
        </button>
        <button
          onclick={confirmSave}
          class="btn btn-danger"
        >
          {t('settings.save')}
        </button>
</div>
    </div>
  </div>
{/if}

<style>
  .settings-drawer {
    animation: slideIn 0.25s ease-out;
  }

  @keyframes slideIn {
    from { transform: translateX(100%); }
    to { transform: translateX(0); }
  }

  /* Settings Group (collapsible card) */
  .settings-group {
    background: var(--bg-elevated, var(--bg-primary));
    border: 1px solid var(--border-color, rgba(128,128,128,0.15));
    border-radius: var(--radius-lg, 0.75rem);
    overflow: hidden;
    transition: box-shadow 0.2s ease;
  }

  .settings-group:hover {
    box-shadow: 0 2px 12px rgba(0,0,0,0.06);
  }

  .settings-group-header {
    display: flex;
    align-items: center;
    gap: 0.75rem;
    width: 100%;
    padding: 1rem 1.25rem;
    background: transparent;
    border: none;
    cursor: pointer;
    text-align: left;
    transition: background 0.15s ease;
  }

  .settings-group-header:hover {
    background: var(--bg-tertiary, rgba(128,128,128,0.06));
  }

  .settings-group-title {
    font-size: 1rem;
    font-weight: 600;
    color: var(--text-primary);
    white-space: nowrap;
  }

  .settings-group-desc {
    font-size: 0.8125rem;
    color: var(--text-tertiary);
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .settings-group-chevron {
    flex-shrink: 0;
    color: var(--text-tertiary);
    transition: transform 0.2s ease;
  }

  .settings-group-chevron.collapsed {
    transform: rotate(-90deg);
  }

  .settings-group-body {
    overflow: hidden;
    transition: max-height 0.25s ease, padding 0.25s ease;
    padding: 0 1.25rem 1.25rem;
  }

  .settings-group-body.hidden {
    max-height: 0;
    padding: 0 1.25rem;
  }
</style>