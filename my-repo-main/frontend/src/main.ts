// IOU browser client
import './style.css';
import type { AppState } from './types';
import {
  configureApi,
  apiCheckHealth,
  apiCreateUser,
  apiListGroups,
  apiCreateGroup,
  apiJoinGroup,
  apiGetGroup,
  apiRenameGroup,
  apiGenerateGroupCode,
  apiLeaveGroup,
  apiCreateBill,
  apiChangeDate,
  apiConfirmPayment,
  apiCancelBill,
  apiCancelAllBills,
} from './api';
import {
  formatCents,
  formatWeight,
  parseAmountToCents,
  parseWeightHundredths,
  isValidUsername,
  escapeHtml,
  computeBalances,
} from './format';

// ── Application State ──────────────────────────────────────────────────────────
let browserStorageUnavailable = false;
let savedSessionUnreadable = false;

function safeGetItem(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    browserStorageUnavailable = true;
    return null;
  }
}

function safeSetItem(key: string, value: string): void {
  try {
    localStorage.setItem(key, value);
  } catch {
    browserStorageUnavailable = true;
  }
}

function safeRemoveItem(key: string): void {
  try {
    localStorage.removeItem(key);
  } catch {
    browserStorageUnavailable = true;
  }
}

const savedServer = safeGetItem('splitter_server');
const defaultServer =
  savedServer && (!savedServer.includes('localhost') || window.location.hostname === 'localhost')
    ? savedServer
    : window.location.origin;

function readSavedCodes(): Record<number, string> {
  try {
    const parsed: unknown = JSON.parse(safeGetItem('splitter_codes') || '{}');
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return parsed as Record<number, string>;
    }
  } catch {
    safeRemoveItem('splitter_codes');
  }
  return {};
}

// Older builds stored multiple accounts. The requirements allow only one active identity.
safeRemoveItem('splitter_saved_accounts');

const savedUsername = safeGetItem('splitter_username');
const savedLoginToken = safeGetItem('splitter_token');
if ((savedUsername && !savedLoginToken) || (!savedUsername && savedLoginToken)
    || (savedLoginToken !== null && savedLoginToken.length < 32)) {
  savedSessionUnreadable = true;
  safeRemoveItem('splitter_username');
  safeRemoveItem('splitter_token');
}

const state: AppState = {
  username: savedSessionUnreadable ? null : savedUsername,
  loginToken: savedSessionUnreadable ? null : savedLoginToken,
  serverAddress: defaultServer,
  savedCodes: readSavedCodes(),
  connected: false,
  groups: [],
  currentGroup: null,
  selectedBillId: null,
  currentScreen: 'username',
};

let pollTimer: ReturnType<typeof setInterval> | null = null;
let pendingConfirmAction: (() => void) | null = null;
let promptingGroupId: number | null = null;

// Configure API client with credentials & status callbacks
configureApi(
  () => {
    if (state.username && state.loginToken) {
      return { username: state.username, loginToken: state.loginToken };
    }
    return null;
  },
  () => state.serverAddress,
  () => markConnected(),
  (msg) => markDisconnected(msg),
);

// ── DOM Elements ─────────────────────────────────────────────────────────────
const banner = document.getElementById('connection-banner') as HTMLDivElement;
const bannerText = document.getElementById('connection-banner-text') as HTMLSpanElement;
const errorDialog = document.getElementById('error-dialog') as HTMLDivElement;
const errorText = document.getElementById('error-message-text') as HTMLSpanElement;
const errorDismiss = document.getElementById('error-dismiss-btn') as HTMLButtonElement;
const confirmModal = document.getElementById('confirm-modal') as HTMLDivElement;
const confirmTitle = document.getElementById('confirm-modal-title') as HTMLHeadingElement;
const confirmMsg = document.getElementById('confirm-modal-message') as HTMLParagraphElement;
const confirmOk = document.getElementById('confirm-ok-btn') as HTMLButtonElement;
const confirmCancel = document.getElementById('confirm-cancel-btn') as HTMLButtonElement;

// Screens
const screenUsername = document.getElementById('screen-username') as HTMLElement;
const usernameInput = document.getElementById('username-input') as HTMLInputElement;
const serverAddrInput = document.getElementById('server-address-input') as HTMLInputElement;
const createUsernameBtn = document.getElementById('create-username-btn') as HTMLButtonElement;

const screenHome = document.getElementById('screen-home') as HTMLElement;
const currentUsernameDisplay = document.getElementById('current-username-display') as HTMLElement;
const groupsList = document.getElementById('groups-list') as HTMLDivElement;
const openCreateGroupBtn = document.getElementById('open-create-group-btn') as HTMLButtonElement;
const openJoinGroupBtn = document.getElementById('open-join-group-btn') as HTMLButtonElement;
const signOutBtn = document.getElementById('sign-out-btn') as HTMLButtonElement;

const createGroupModal = document.getElementById('create-group-modal') as HTMLDivElement;
const newGroupName = document.getElementById('new-group-name') as HTMLInputElement;
const creatorMemberName = document.getElementById('creator-member-name') as HTMLInputElement;
const createGroupSubmitBtn = document.getElementById('create-group-submit-btn') as HTMLButtonElement;
const createGroupCancelBtn = document.getElementById('create-group-cancel-btn') as HTMLButtonElement;

const joinGroupModal = document.getElementById('join-group-modal') as HTMLDivElement;
const joinGroupCode = document.getElementById('join-group-code') as HTMLInputElement;
const joinMemberName = document.getElementById('join-member-name') as HTMLInputElement;
const joinGroupSubmitBtn = document.getElementById('join-group-submit-btn') as HTMLButtonElement;
const joinGroupCancelBtn = document.getElementById('join-group-cancel-btn') as HTMLButtonElement;

const promptCodeModal = document.getElementById('prompt-code-modal') as HTMLDivElement;
const promptGroupCode = document.getElementById('prompt-group-code') as HTMLInputElement;
const promptCodeSubmitBtn = document.getElementById('prompt-code-submit-btn') as HTMLButtonElement;
const promptCodeCancelBtn = document.getElementById('prompt-code-cancel-btn') as HTMLButtonElement;

const screenGroup = document.getElementById('screen-group') as HTMLElement;
const groupTitle = document.getElementById('group-title') as HTMLHeadingElement;
const groupOwnerBadge = document.getElementById('group-owner-badge') as HTMLSpanElement;
const groupBackBtn = document.getElementById('group-back-btn') as HTMLButtonElement;
const groupLeaveBtn = document.getElementById('group-leave-btn') as HTMLButtonElement;
const ownerControls = document.getElementById('owner-controls') as HTMLSpanElement;
const groupRenameBtn = document.getElementById('group-rename-btn') as HTMLButtonElement;
const groupShowCodeBtn = document.getElementById('group-show-code-btn') as HTMLButtonElement;
const groupGenerateCodeBtn = document.getElementById('group-generate-code-btn') as HTMLButtonElement;
const groupCancelAllBillsBtn = document.getElementById('group-cancel-all-bills-btn') as HTMLButtonElement;
const groupCodeDisplay = document.getElementById('group-code-display') as HTMLDivElement;
const groupCodeText = document.getElementById('group-code-text') as HTMLElement;

const membersList = document.getElementById('members-list') as HTMLDivElement;
const billsScrollList = document.getElementById('bills-scroll-list') as HTMLDivElement;
const billDetailsContainer = document.getElementById('bill-details-container') as HTMLDivElement;
const detailBillDesc = document.getElementById('detail-bill-desc') as HTMLHeadingElement;
const detailBillStatus = document.getElementById('detail-bill-status') as HTMLSpanElement;
const detailBillTotal = document.getElementById('detail-bill-total') as HTMLElement;
const detailDateInput = document.getElementById('detail-date-input') as HTMLInputElement;
const detailChangeDateBtn = document.getElementById('detail-change-date-btn') as HTMLButtonElement;
const detailSharesTbody = document.getElementById('detail-shares-tbody') as HTMLTableSectionElement;
const detailCancelBillBtn = document.getElementById('detail-cancel-bill-btn') as HTMLButtonElement;

const billDescInput = document.getElementById('bill-description-input') as HTMLInputElement;
const billAmountInput = document.getElementById('bill-amount-input') as HTMLInputElement;
const billDateInput = document.getElementById('bill-date-input') as HTMLInputElement;
const splitEquallyBtn = document.getElementById('split-equally-btn') as HTMLButtonElement;
const weightRowsContainer = document.getElementById('weight-rows-container') as HTMLDivElement;
const weightSumDisplay = document.getElementById('weight-sum-display') as HTMLSpanElement;
const submitCreateBillBtn = document.getElementById('submit-create-bill-btn') as HTMLButtonElement;

// ── Notifications and Status ─────────────────────────────────────────────────
function markConnected() {
  state.connected = true;
  banner.classList.add('hidden');
  updateControlStates();
}

function markDisconnected(msg: string) {
  state.connected = false;
  bannerText.textContent = `Server unreachable: ${msg || 'Connection lost'}`;
  banner.classList.remove('hidden');
  updateControlStates();
}

function showError(msg?: string) {
  if (!msg) return;
  errorText.textContent = msg;
  errorDialog.classList.remove('hidden');
}

errorDismiss.addEventListener('click', () => {
  errorDialog.classList.add('hidden');
});

function confirmAction(title: string, message: string, onConfirm: () => void) {
  confirmTitle.textContent = title;
  confirmMsg.textContent = message;
  pendingConfirmAction = onConfirm;
  confirmModal.classList.remove('hidden');
}

confirmOk.addEventListener('click', () => {
  confirmModal.classList.add('hidden');
  if (pendingConfirmAction) {
    const act = pendingConfirmAction;
    pendingConfirmAction = null;
    act();
  }
});

confirmCancel.addEventListener('click', () => {
  confirmModal.classList.add('hidden');
  pendingConfirmAction = null;
});

// ── Screen Router ────────────────────────────────────────────────────────────
function showScreen(screen: 'username' | 'home' | 'group') {
  state.currentScreen = screen;
  screenUsername.classList.add('hidden');
  screenHome.classList.add('hidden');
  screenGroup.classList.add('hidden');

  if (pollTimer) {
    clearInterval(pollTimer);
    pollTimer = null;
  }

  if (screen === 'username') {
    screenUsername.classList.remove('hidden');
    void apiCheckHealth();
    pollTimer = setInterval(() => void apiCheckHealth(), 5000);
  } else if (screen === 'home') {
    screenHome.classList.remove('hidden');
    currentUsernameDisplay.textContent = state.username || '';
    void loadHomeGroups();
    pollTimer = setInterval(() => void loadHomeGroups(), 5000);
  } else if (screen === 'group') {
    screenGroup.classList.remove('hidden');
    renderGroupScreen();
    pollTimer = setInterval(() => void pollGroupSnapshot(), 5000);
  }
  updateControlStates();
}

function updateControlStates() {
  const offline = !state.connected;
  createUsernameBtn.disabled = offline || !isValidUsername(usernameInput.value);
  createGroupSubmitBtn.disabled = offline;
  joinGroupSubmitBtn.disabled = offline || !joinGroupCode.value.trim() || !joinMemberName.value.trim();
  promptCodeSubmitBtn.disabled = offline || !promptGroupCode.value.trim();
  submitCreateBillBtn.disabled = offline || !isCreateBillValid();
  groupLeaveBtn.disabled = offline;
  groupRenameBtn.disabled = offline;
  groupGenerateCodeBtn.disabled = offline;
  groupCancelAllBillsBtn.disabled = offline;
  detailCancelBillBtn.disabled = offline;
  detailChangeDateBtn.disabled = offline || detailDateInput.disabled;
}

// ── Screen 1: Username / Auth ─────────────────────────────────────────────────
serverAddrInput.value = state.serverAddress;

usernameInput.addEventListener('input', () => {
  createUsernameBtn.disabled = !state.connected || !isValidUsername(usernameInput.value);
});

createUsernameBtn.addEventListener('click', async () => {
  const username = usernameInput.value.trim();
  const serverAddr = serverAddrInput.value.trim() || window.location.origin;
  state.serverAddress = serverAddr;
  safeSetItem('splitter_server', serverAddr);

  const res = await apiCreateUser(username);
  if (res.success && res.data) {
    state.username = res.data.username;
    state.loginToken = res.data.loginToken;
    safeSetItem('splitter_username', state.username);
    safeSetItem('splitter_token', state.loginToken);
    showScreen('home');
  } else {
    showError(res.failureReason);
  }
});

// ── Sign Out ─────────────────────────────────────────────────────────────────
signOutBtn.addEventListener('click', () => {
  safeRemoveItem('splitter_username');
  safeRemoveItem('splitter_token');
  state.username = null;
  state.loginToken = null;
  state.groups = [];
  state.currentGroup = null;
  state.selectedBillId = null;
  usernameInput.value = '';
  showScreen('username');
});

// ── Screen 2: Home / Group List ──────────────────────────────────────────────
async function loadHomeGroups(): Promise<void> {
  const res = await apiListGroups();
  if (res.success && Array.isArray(res.data)) {
    const changed = JSON.stringify(state.groups) !== JSON.stringify(res.data);
    if (changed) {
      state.groups = res.data;
      renderGroupsList();
    }
  }
}

function renderGroupsList(): void {
  groupsList.innerHTML = '';
  if (state.groups.length === 0) {
    groupsList.innerHTML = '<p class="empty-state">No groups yet</p>';
    return;
  }
  state.groups.forEach((g) => {
    const item = document.createElement('div');
    item.className = 'list-item';
    item.innerHTML = `<strong>${escapeHtml(g.name)}</strong><span>&rarr;</span>`;
    item.addEventListener('click', () => onSelectGroup(g.groupId));
    groupsList.appendChild(item);
  });
}

function onSelectGroup(groupId: number): void {
  const code = state.savedCodes[groupId];
  if (code) {
    void fetchAndOpenGroup(groupId, code);
  } else {
    promptingGroupId = groupId;
    promptGroupCode.value = '';
    promptCodeModal.classList.remove('hidden');
  }
}

async function fetchAndOpenGroup(groupId: number, code: string): Promise<void> {
  const res = await apiGetGroup(groupId, code);
  if (res.success && res.data) {
    state.currentGroup = res.data;
    state.savedCodes[groupId] = code;
    safeSetItem('splitter_codes', JSON.stringify(state.savedCodes));
    showScreen('group');
  } else {
    if (res.failureReason === 'code outdated') {
      delete state.savedCodes[groupId];
      safeSetItem('splitter_codes', JSON.stringify(state.savedCodes));
      promptingGroupId = groupId;
      promptGroupCode.value = '';
      promptCodeModal.classList.remove('hidden');
    } else {
      showError(res.failureReason);
    }
  }
}

openCreateGroupBtn.addEventListener('click', () => {
  newGroupName.value = '';
  creatorMemberName.value = '';
  createGroupModal.classList.remove('hidden');
});

createGroupCancelBtn.addEventListener('click', () => {
  createGroupModal.classList.add('hidden');
});

createGroupSubmitBtn.addEventListener('click', async () => {
  const groupName = newGroupName.value.trim();
  const memberName = creatorMemberName.value.trim();
  if (!groupName || !memberName) {
    showError('Please fill in both group name and display name');
    return;
  }

  const res = await apiCreateGroup(groupName, memberName);
  if (res.success && res.data) {
    createGroupModal.classList.add('hidden');
    state.currentGroup = res.data;
    if (res.data.groupCode) {
      state.savedCodes[res.data.groupId] = res.data.groupCode;
      safeSetItem('splitter_codes', JSON.stringify(state.savedCodes));
    }
    showScreen('group');
  } else {
    showError(res.failureReason);
  }
});

openJoinGroupBtn.addEventListener('click', () => {
  joinGroupCode.value = '';
  joinMemberName.value = '';
  joinGroupModal.classList.remove('hidden');
});

joinGroupCancelBtn.addEventListener('click', () => {
  joinGroupModal.classList.add('hidden');
});

function updateJoinBtn() {
  const code = joinGroupCode.value.trim();
  const name = joinMemberName.value.trim();
  joinGroupSubmitBtn.disabled = !state.connected || !code || !name;
}
joinGroupCode.addEventListener('input', updateJoinBtn);
joinMemberName.addEventListener('input', updateJoinBtn);

joinGroupSubmitBtn.addEventListener('click', async () => {
  const code = joinGroupCode.value.trim().toUpperCase();
  const memberName = joinMemberName.value.trim();
  const res = await apiJoinGroup(code, memberName);
  if (res.success && res.data) {
    joinGroupModal.classList.add('hidden');
    state.currentGroup = res.data;
    state.savedCodes[res.data.groupId] = code;
    safeSetItem('splitter_codes', JSON.stringify(state.savedCodes));
    showScreen('group');
  } else {
    showError(res.failureReason);
  }
});

promptCodeCancelBtn.addEventListener('click', () => {
  promptCodeModal.classList.add('hidden');
  promptingGroupId = null;
});

promptGroupCode.addEventListener('input', updateControlStates);
promptCodeSubmitBtn.addEventListener('click', () => {
  const code = promptGroupCode.value.trim().toUpperCase();
  if (promptingGroupId && code) {
    promptCodeModal.classList.add('hidden');
    void fetchAndOpenGroup(promptingGroupId, code);
    promptingGroupId = null;
  }
});

// ── Screen 3: Group View ─────────────────────────────────────────────────────
async function pollGroupSnapshot(): Promise<void> {
  if (!state.currentGroup) return;
  const gid = state.currentGroup.groupId;
  const code = state.savedCodes[gid];
  const res = await apiGetGroup(gid, code);
  if (res.success && res.data) {
    const changed = JSON.stringify(state.currentGroup) !== JSON.stringify(res.data);
    if (changed) {
      state.currentGroup = res.data;
      renderGroupScreen();
    }
  } else {
    if (['group not found', 'not a member', 'code outdated'].includes(res.failureReason || '')) {
      const message = res.failureReason === 'code outdated'
        ? 'The group code has changed. Enter the new code to reopen this group.'
        : res.failureReason;
      if (res.failureReason === 'code outdated') {
        delete state.savedCodes[gid];
        safeSetItem('splitter_codes', JSON.stringify(state.savedCodes));
      }
      showScreen('home');
      showError(message);
      if (res.failureReason === 'code outdated') {
        promptingGroupId = gid;
        promptGroupCode.value = '';
        promptCodeModal.classList.remove('hidden');
      }
    }
  }
}

function renderGroupScreen(): void {
  const g = state.currentGroup;
  if (!g) return;

  groupTitle.textContent = g.groupName;
  if (g.isOwner) {
    groupOwnerBadge.classList.remove('hidden');
    ownerControls.classList.remove('hidden');
    groupCodeText.textContent = g.groupCode || '';
  } else {
    groupOwnerBadge.classList.add('hidden');
    ownerControls.classList.add('hidden');
    groupCodeDisplay.classList.add('hidden');
  }

  renderMembers();
  renderBills();
  renderBillDetails();
  renderCreateBillWeights();
}

function renderMembers(): void {
  const g = state.currentGroup;
  if (!g) return;
  membersList.innerHTML = '';
  const currentMembers = g.members.filter((m) => !m.hasLeft);

  const balances = computeBalances(currentMembers, g.bills);

  currentMembers.forEach((m) => {
    const isYou = g.currentMemberOrder >= 0
      ? m.memberOrder === g.currentMemberOrder
      : m.name.toLowerCase() === g.currentMemberName.toLowerCase();
    const bal = balances[m.memberOrder] || { owes: 0, isOwed: 0 };
    const row = document.createElement('div');
    row.className = 'member-row';
    row.innerHTML = `
      <div class="member-info">
        <strong>${escapeHtml(m.name)} ${m.isOwner ? '<span class="badge badge-owner">Owner</span>' : ''} ${isYou ? '<span class="badge">you</span>' : ''}</strong>
      </div>
      <div class="member-balances">
        <span>Owes: ${formatCents(bal.owes)}</span> &bull;
        <span>Is owed: ${formatCents(bal.isOwed)}</span>
      </div>
    `;
    membersList.appendChild(row);
  });
}

function renderBills(): void {
  const g = state.currentGroup;
  if (!g) return;
  billsScrollList.innerHTML = '';
  if (!g.bills || g.bills.length === 0) {
    billsScrollList.innerHTML = '<p class="empty-state">No bills yet</p>';
    return;
  }

  g.bills.forEach((b) => {
    const row = document.createElement('div');
    row.className = `bill-row ${state.selectedBillId === b.id ? 'selected' : ''}`;
    const ownerMember = g.members.find((m) => m.memberOrder === b.billOwnerMemberOrder)
      || g.members.find((m) => m.name.toLowerCase() === b.billOwnerMemberName.toLowerCase());
    const ownerDisplay = ownerMember && ownerMember.hasLeft ? `${b.billOwnerMemberName} (left)` : b.billOwnerMemberName;
    const dateDisplay = b.suggestedDate ? b.suggestedDate : 'No date';
    const otherMembersShares = (b.shares || []).filter(
      (share) => share.shareCents > 0
        && (b.billOwnerMemberOrder >= 0
          ? share.memberOrder !== b.billOwnerMemberOrder
          : share.memberName.toLowerCase() !== b.billOwnerMemberName.toLowerCase()),
    );
    const paidShares = otherMembersShares.filter((share) => share.isConfirmed).length;
    const payableShares = otherMembersShares.length;

    row.innerHTML = `
      <div class="member-info">
        <strong>${escapeHtml(b.description)}</strong>
        <strong>${formatCents(b.totalCents)}</strong>
      </div>
      <div class="bill-meta">
        <span>By ${escapeHtml(ownerDisplay)}</span>
        <span>${dateDisplay}</span>
        <span>${paidShares} of ${payableShares} paid</span>
      </div>
    `;
    row.addEventListener('click', () => {
      state.selectedBillId = b.id;
      renderBills();
      renderBillDetails();
    });
    billsScrollList.appendChild(row);
  });
}

function renderBillDetails(): void {
  const g = state.currentGroup;
  if (!state.selectedBillId || !g) {
    billDetailsContainer.classList.add('hidden');
    return;
  }

  const bill = (g.bills || []).find((b) => b.id === state.selectedBillId);
  if (!bill) {
    state.selectedBillId = null;
    billDetailsContainer.classList.add('hidden');
    return;
  }

  billDetailsContainer.classList.remove('hidden');
  detailBillDesc.textContent = bill.description;
  detailBillTotal.textContent = formatCents(bill.totalCents);
  const fullyPaid = (bill.shares || []).every((s) => s.shareCents === 0 || s.isConfirmed);
  detailBillStatus.textContent = fullyPaid ? 'Settled' : 'Open';
  detailDateInput.value = bill.suggestedDate || '';

  const isBillOwner = g.currentMemberOrder >= 0
    ? g.currentMemberOrder === bill.billOwnerMemberOrder
    : g.currentMemberName.toLowerCase() === bill.billOwnerMemberName.toLowerCase();
  const canCancel = isBillOwner || g.isOwner;
  detailCancelBillBtn.style.display = canCancel ? 'inline-block' : 'none';

  detailChangeDateBtn.disabled = !isBillOwner || !state.connected;
  detailDateInput.disabled = !isBillOwner || !state.connected;

  let canConfirm = isBillOwner;
  if (!canConfirm && g.isOwner) {
    const ownerMember = g.members.find((m) => m.memberOrder === bill.billOwnerMemberOrder)
      || g.members.find((m) => m.name.toLowerCase() === bill.billOwnerMemberName.toLowerCase());
    if (ownerMember && ownerMember.hasLeft) canConfirm = true;
  }

  detailSharesTbody.innerHTML = '';
  (bill.shares || []).forEach((s) => {
    if (s.shareCents <= 0) return;
    const tr = document.createElement('tr');
    const shareMember = g.members.find((member) => member.memberOrder === s.memberOrder);
    const shareName = shareMember?.hasLeft ? `${s.memberName} (left)` : s.memberName;
    const isPaid = s.isConfirmed;
    const confirmBtnHtml =
      !isPaid && canConfirm && state.connected
        ? `<button class="btn btn-secondary btn-sm confirm-share-btn" data-member-order="${s.memberOrder}">Confirm Payment</button>`
        : '';
    tr.innerHTML = `
      <td>${escapeHtml(shareName)}</td>
      <td>${formatWeight(s.weightHundredths)}</td>
      <td>${formatCents(s.shareCents)}</td>
      <td><span class="badge">${isPaid ? 'Paid' : 'Unpaid'}</span></td>
      <td>${confirmBtnHtml}</td>
    `;
    detailSharesTbody.appendChild(tr);
  });

  document.querySelectorAll<HTMLButtonElement>('.confirm-share-btn').forEach((btn) => {
    btn.addEventListener('click', async (e) => {
      const target = e.currentTarget as HTMLElement;
      const memberOrderText = target.getAttribute('data-member-order');
      const memberOrder = memberOrderText === null ? Number.NaN : Number(memberOrderText);
      if (!Number.isInteger(memberOrder) || memberOrder < 0) return;
      const res = await apiConfirmPayment(
        g.groupId,
        state.savedCodes[g.groupId],
        bill.id,
        memberOrder,
      );
      if (res.success) {
        void pollGroupSnapshot();
      } else {
        showError(res.failureReason);
      }
    });
  });
}

detailChangeDateBtn.addEventListener('click', async () => {
  if (!state.selectedBillId || !state.currentGroup) return;
  const newDate = detailDateInput.value;
  const res = await apiChangeDate(
    state.currentGroup.groupId,
    state.savedCodes[state.currentGroup.groupId],
    state.selectedBillId,
    newDate || null,
  );
  if (res.success) {
    void pollGroupSnapshot();
  } else {
    showError(res.failureReason);
  }
});

detailCancelBillBtn.addEventListener('click', () => {
  if (!state.selectedBillId || !state.currentGroup) return;
  const selectedBill = state.currentGroup.bills.find((bill) => bill.id === state.selectedBillId);
  if (!selectedBill) return;
  confirmAction(
    'Cancel Bill',
    `Permanently delete "${selectedBill.description}" (${formatCents(selectedBill.totalCents)})? Previously confirmed payments will also be removed.`,
    async () => {
      if (!state.currentGroup || !state.selectedBillId) return;
      const res = await apiCancelBill(
        state.currentGroup.groupId,
        state.savedCodes[state.currentGroup.groupId],
        state.selectedBillId,
      );
      if (res.success) {
        state.selectedBillId = null;
        void pollGroupSnapshot();
      } else {
        showError(res.failureReason);
      }
    },
  );
});

groupBackBtn.addEventListener('click', () => {
  showScreen('home');
});

groupLeaveBtn.addEventListener('click', () => {
  confirmAction('Leave Group', 'Are you sure you want to leave this group?', async () => {
    if (!state.currentGroup) return;
    const gid = state.currentGroup.groupId;
    const res = await apiLeaveGroup(gid, state.savedCodes[gid]);
    if (res.success) {
      delete state.savedCodes[gid];
      safeSetItem('splitter_codes', JSON.stringify(state.savedCodes));
      showScreen('home');
    } else {
      showError(res.failureReason);
    }
  });
});

groupRenameBtn.addEventListener('click', () => {
  if (!state.currentGroup) return;
  const currentName = state.currentGroup.groupName;
  const newName = prompt('Enter new group name:', currentName);
  if (newName && newName.trim() && newName.trim() !== currentName) {
    void apiRenameGroup(
      state.currentGroup.groupId,
      state.savedCodes[state.currentGroup.groupId],
      newName.trim(),
    ).then((res) => {
      if (res.success) void pollGroupSnapshot();
      else showError(res.failureReason);
    });
  }
});

groupShowCodeBtn.addEventListener('click', () => {
  groupCodeDisplay.classList.toggle('hidden');
});

groupGenerateCodeBtn.addEventListener('click', async () => {
  if (!state.currentGroup) return;
  const gid = state.currentGroup.groupId;
  const res = await apiGenerateGroupCode(gid, state.savedCodes[gid]);
  if (res.success && res.data) {
    state.savedCodes[gid] = res.data;
    safeSetItem('splitter_codes', JSON.stringify(state.savedCodes));
    void pollGroupSnapshot();
  } else {
    showError(res.failureReason);
  }
});

groupCancelAllBillsBtn.addEventListener('click', () => {
  confirmAction(
    'Cancel All Bills',
    'Every bill in this group, whether open or settled, will be permanently removed. Confirmed payment records will also be deleted. Are you sure?',
    async () => {
      if (!state.currentGroup) return;
      const gid = state.currentGroup.groupId;
      const res = await apiCancelAllBills(gid, state.savedCodes[gid]);
      if (res.success) {
        void pollGroupSnapshot();
      } else {
        showError(res.failureReason);
      }
    },
  );
});

// ── Create Bill Form ─────────────────────────────────────────────────────────
function renderCreateBillWeights(): void {
  const g = state.currentGroup;
  if (!g) return;
  const currentMembers = g.members.filter((m) => !m.hasLeft);

  const existing: Record<string, string> = {};
  const currentInputs = Array.from(document.querySelectorAll<HTMLInputElement>('.weight-input'));
  currentInputs.forEach((inp) => {
    const memberName = inp.getAttribute('data-member');
    if (memberName) existing[memberName] = inp.value;
  });

  const displayedNames = currentInputs.map((input) => input.getAttribute('data-member') || '');
  const currentNames = currentMembers.map((member) => member.name);
  if (displayedNames.length === currentNames.length
      && displayedNames.every((name, index) => name === currentNames[index])) {
    updateCreateBillState();
    return;
  }

  weightRowsContainer.innerHTML = '';
  currentMembers.forEach((m) => {
    const row = document.createElement('div');
    row.className = 'weight-row-item';
    const val = existing[m.name] !== undefined ? existing[m.name] : '0.00';
    row.innerHTML = `
      <span>${escapeHtml(m.name)}</span>
      <input type="text" class="weight-input" data-member="${escapeHtml(m.name)}" value="${val}" placeholder="0.00">
    `;
    weightRowsContainer.appendChild(row);
  });

  document.querySelectorAll<HTMLInputElement>('.weight-input').forEach((inp) => {
    inp.addEventListener('input', updateCreateBillState);
  });

  updateCreateBillState();
}

splitEquallyBtn.addEventListener('click', () => {
  const inputs = document.querySelectorAll<HTMLInputElement>('.weight-input');
  const n = inputs.length;
  if (n === 0) return;
  const base = Math.floor(100 / n);
  const remainder = 100 % n;
  inputs.forEach((inp, idx) => {
    const w = base + (idx < remainder ? 1 : 0);
    inp.value = formatWeight(w);
  });
  updateCreateBillState();
});

function isValidCalendarDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const date = new Date(`${value}T00:00:00Z`);
  return !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === value;
}

function isCreateBillValid(): boolean {
  const desc = billDescInput.value.trim();
  if (!desc) return false;
  const amount = parseAmountToCents(billAmountInput.value);
  if (!amount) return false;
  const suggestedDate = billDateInput.value;
  if (suggestedDate && !isValidCalendarDate(suggestedDate)) return false;

  let sum = 0;
  const inputs = document.querySelectorAll<HTMLInputElement>('.weight-input');
  if (inputs.length === 0) return false;

  for (const inp of Array.from(inputs)) {
    const w = parseWeightHundredths(inp.value);
    if (w === null) return false;
    sum += w;
  }
  return sum === 100;
}

function updateCreateBillState(): void {
  let sum = 0;
  let hasErr = false;
  const inputs = document.querySelectorAll<HTMLInputElement>('.weight-input');

  inputs.forEach((inp) => {
    const w = parseWeightHundredths(inp.value);
    if (w === null) {
      inp.classList.add('input-error');
      hasErr = true;
    } else {
      inp.classList.remove('input-error');
      sum += w;
    }
  });

  if (hasErr) {
    weightSumDisplay.textContent = 'Error';
    weightSumDisplay.parentElement?.classList.add('error');
  } else {
    weightSumDisplay.textContent = formatWeight(sum);
    if (sum === 100) {
      weightSumDisplay.parentElement?.classList.remove('error');
    } else {
      weightSumDisplay.parentElement?.classList.add('error');
    }
  }

  submitCreateBillBtn.disabled = !state.connected || !isCreateBillValid();
}

billDescInput.addEventListener('input', updateCreateBillState);
billAmountInput.addEventListener('input', updateCreateBillState);
billDateInput.addEventListener('input', updateCreateBillState);

submitCreateBillBtn.addEventListener('click', async () => {
  if (!isCreateBillValid() || !state.currentGroup) return;

  const desc = billDescInput.value.trim();
  const totalCents = parseAmountToCents(billAmountInput.value);
  if (!totalCents) return;
  const suggestedDate = billDateInput.value || null;

  const weights: Record<string, number> = {};
  document.querySelectorAll<HTMLInputElement>('.weight-input').forEach((inp) => {
    const mem = inp.getAttribute('data-member');
    const w = parseWeightHundredths(inp.value);
    if (mem && w !== null) {
      weights[mem] = w;
    }
  });

  const res = await apiCreateBill(
    state.currentGroup.groupId,
    state.savedCodes[state.currentGroup.groupId],
    {
      description: desc,
      totalCents,
      suggestedDate,
      weightsHundredths: weights,
    },
  );

  if (res.success) {
    billDescInput.value = '';
    billAmountInput.value = '';
    billDateInput.value = '';
    renderCreateBillWeights();
    void pollGroupSnapshot();
  } else {
    showError(res.failureReason);
  }
});

// ── Application Boot ──────────────────────────────────────────────────────────
if (state.username && state.loginToken) {
  showScreen('home');
} else {
  showScreen('username');
}

if (savedSessionUnreadable) {
  showError('Saved sign-in information is missing or unreadable. Your previous username cannot be recovered; create a new username.');
} else if (browserStorageUnavailable) {
  showError('Browser storage is unavailable. Your sign-in information and group codes may not be saved on this device.');
}
