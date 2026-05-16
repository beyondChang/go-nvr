<script lang="ts">
	import { toasts, dismissToast } from '$lib/toast';
	import { fade, fly } from 'svelte/transition';
	import { X } from 'lucide-svelte';
</script>

<div
	style="position:fixed;top:5rem;right:1rem;z-index:9999;display:flex;flex-direction:column;gap:0.5rem;align-items:flex-end;"
>
	{#each $toasts as toast (toast.id)}
		<div
			class="toast"
			class:toast-success={toast.type === 'success'}
			class:toast-error={toast.type === 'error'}
			class:toast-info={toast.type === 'info'}
			class:toast-warning={toast.type === 'warning'}
			transition:fly={{ y: -20, duration: 300 }}
			on:click={() => dismissToast(toast.id)}
		>
			{toast.message}
			<button
				class="toast-close"
				on:click|stopPropagation={() => dismissToast(toast.id)}
			>
			<X size={16} />
			</button>
		</div>
	{/each}
</div>

<style>
	.toast {
		position: relative;
		min-width: 300px;
		max-width: 400px;
		padding: 1rem;
		border-radius: var(--radius-sm);
		box-shadow: var(--shadow-md);
		cursor: pointer;
		display: flex;
		align-items: center;
		justify-content: space-between;
		font-weight: 500;
		transition: opacity 0.3s var(--ease-out);
		color: var(--text-primary);
	}

	.toast-close {
		background: none;
		border: none;
		color: var(--text-tertiary);
		cursor: pointer;
		padding: 0.25rem;
		font-size: 1rem;
		transition: color var(--duration-fast) var(--ease-out);
	}

	.toast-close:hover {
		color: var(--text-primary);
	}

	.toast-success {
		background-color: var(--color-success);
	}

	.toast-error {
		background-color: var(--color-danger);
	}

	.toast-info {
		background-color: var(--color-primary);
	}

	.toast-warning {
		background-color: var(--color-warning);
	}

	/* Fade-out animation when removing */
	.toast.fade-out {
		opacity: 0;
		transform: translateY(-10px);
	}
</style>