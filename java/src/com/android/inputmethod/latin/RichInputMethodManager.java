/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.inputmethod.latin;

import static com.android.inputmethod.latin.common.Constants.Subtype.KEYBOARD_MODE;
import static com.android.inputmethod.latin.common.Constants.Subtype.ExtraValue.REQ_NETWORK_CONNECTIVITY;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.inputmethodservice.InputMethodService;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Build;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

import com.android.inputmethod.annotations.UsedForTesting;
import com.android.inputmethod.compat.InputMethodManagerCompatWrapper;
import com.android.inputmethod.keyboard.KeyboardSwitcher;
import com.android.inputmethod.latin.settings.AdditionalFeaturesSettingUtils;
import com.android.inputmethod.latin.settings.Settings;
import com.android.inputmethod.latin.utils.AdditionalSubtypeUtils;
import com.android.inputmethod.latin.utils.SubtypeLocaleUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;

/**
 * Enrichment class for InputMethodManager to simplify interaction and add functionality.
 */
// non final for easy mocking.
public class RichInputMethodManager {
    private static final String TAG = RichInputMethodManager.class.getSimpleName();
    private static final boolean DEBUG = false;

    private RichInputMethodManager() {
        // This utility class is not publicly instantiable.
    }

    private static final RichInputMethodManager sInstance = new RichInputMethodManager();

    private Context mContext;
    private InputMethodManagerCompatWrapper mImmWrapper;
    private InputMethodInfoCache mInputMethodInfoCache;
    private RichInputMethodSubtype mCurrentRichInputMethodSubtype;
    private InputMethodInfo mShortcutInputMethodInfo;
    private InputMethodSubtype mShortcutSubtype;
    private boolean mIsNetworkConnected;
    final HashMap<InputMethodInfo, List<InputMethodSubtype>>
            mSubtypeListCacheWithImplicitlySelectedSubtypes = new HashMap<>();
    final HashMap<InputMethodInfo, List<InputMethodSubtype>>
            mSubtypeListCacheWithoutImplicitlySelectedSubtypes = new HashMap<>();

    private static final int INDEX_NOT_FOUND = -1;

    public static RichInputMethodManager getInstance() {
        sInstance.checkInitialized();
        return sInstance;
    }

    public static void init(final Context context) {
        sInstance.initInternal(context);
    }

    private boolean isInitialized() {
        return mImmWrapper != null;
    }

    private void checkInitialized() {
        if (!isInitialized()) {
            throw new RuntimeException(TAG + " is used before initialization");
        }
    }

    private void initInternal(final Context context) {
        if (isInitialized()) {
            return;
        }
        mImmWrapper = new InputMethodManagerCompatWrapper(context);
        mContext = context;
        mInputMethodInfoCache = new InputMethodInfoCache(
                mImmWrapper.mImm, context.getPackageName());

        // Initialize additional subtypes.
        SubtypeLocaleUtils.init(context);
        final InputMethodSubtype[] additionalSubtypes = getAdditionalSubtypes();
        setAdditionalInputMethodSubtypes(additionalSubtypes);

        final ConnectivityManager connectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        final NetworkInfo info = connectivityManager.getActiveNetworkInfo();
        mIsNetworkConnected = (info != null && info.isConnected());
    }

    public InputMethodSubtype[] getAdditionalSubtypes() {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        final String prefAdditionalSubtypes = Settings.readPrefAdditionalSubtypes(
                prefs, mContext.getResources());
        return AdditionalSubtypeUtils.createAdditionalSubtypesArray(prefAdditionalSubtypes);
    }

    public InputMethodManager getInputMethodManager() {
        checkInitialized();
        return mImmWrapper.mImm;
    }

    public List<InputMethodSubtype> getMyEnabledInputMethodSubtypeList(
            boolean allowsImplicitlySelectedSubtypes) {
        return getEnabledInputMethodSubtypeList(
                getInputMethodInfoOfThisIme(), allowsImplicitlySelectedSubtypes);
    }

    public boolean switchToNextInputMethod(final IBinder token, final boolean onlyCurrentIme) {
        if (mImmWrapper.switchToNextInputMethod(token, onlyCurrentIme)) {
            return true;
        }
        // Was not able to call {@link InputMethodManager#switchToNextInputMethodIBinder,boolean)}
        // because the current device is running ICS or previous and lacks the API.
        if (switchToNextInputSubtypeInThisIme(token, onlyCurrentIme)) {
            return true;
        }
        return switchToNextInputMethodAndSubtype(token);
    }

    private boolean switchToNextInputSubtypeInThisIme(final IBinder token,
            final boolean onlyCurrentIme) {
        final InputMethodManager imm = mImmWrapper.mImm;
        final InputMethodSubtype currentSubtype = imm.getCurrentInputMethodSubtype();
        final List<InputMethodSubtype> enabledSubtypes = getMyEnabledInputMethodSubtypeList(
                true /* allowsImplicitlySelectedSubtypes */);
        final int currentIndex = getSubtypeIndexInList(currentSubtype, enabledSubtypes);
        if (currentIndex == INDEX_NOT_FOUND) {
            Log.w(TAG, "Can't find current subtype in enabled subtypes: subtype="
                    + SubtypeLocaleUtils.getSubtypeNameForLogging(currentSubtype));
            return false;
        }
        final int nextIndex = (currentIndex + 1) % enabledSubtypes.size();
        if (nextIndex <= currentIndex && !onlyCurrentIme) {
            // The current subtype is the last or only enabled one and it needs to switch to
            // next IME.
            return false;
        }
        final InputMethodSubtype nextSubtype = enabledSubtypes.get(nextIndex);
        setInputMethodAndSubtype(token, nextSubtype);
        return true;
    }

    private boolean switchToNextInputMethodAndSubtype(final IBinder token) {
        final InputMethodManager imm = mImmWrapper.mImm;
        final List<InputMethodInfo> enabledImis = imm.getEnabledInputMethodList();
        final int currentIndex = getImiIndexInList(getInputMethodInfoOfThisIme(), enabledImis);
        if (currentIndex == INDEX_NOT_FOUND) {
            Log.w(TAG, "Can't find current IME in enabled IMEs: IME package="
                    + getInputMethodInfoOfThisIme().getPackageName());
            return false;
        }
        final InputMethodInfo nextImi = getNextNonAuxiliaryIme(currentIndex, enabledImis);
        final List<InputMethodSubtype> enabledSubtypes = getEnabledInputMethodSubtypeList(nextImi,
                true /* allowsImplicitlySelectedSubtypes */);
        if (enabledSubtypes.isEmpty()) {
            // The next IME has no subtype.
            imm.setInputMethod(token, nextImi.getId());
            return true;
        }
        final InputMethodSubtype firstSubtype = enabledSubtypes.get(0);
        imm.setInputMethodAndSubtype(token, nextImi.getId(), firstSubtype);
        return true;
    }

    private static int getImiIndexInList(final InputMethodInfo inputMethodInfo,
            final List<InputMethodInfo> imiList) {
        final int count = imiList.size();
        for (int index = 0; index < count; index++) {
            final InputMethodInfo imi = imiList.get(index);
            if (imi.equals(inputMethodInfo)) {
                return index;
            }
        }
        return INDEX_NOT_FOUND;
    }

    // This method mimics {@link InputMethodManager#switchToNextInputMethod(IBinder,boolean)}.
    private static InputMethodInfo getNextNonAuxiliaryIme(final int currentIndex,
            final List<InputMethodInfo> imiList) {
        final int count = imiList.size();
        for (int i = 1; i < count; i++) {
            final int nextIndex = (currentIndex + i) % count;
            final InputMethodInfo nextImi = imiList.get(nextIndex);
            if (!isAuxiliaryIme(nextImi)) {
                return nextImi;
            }
        }
        return imiList.get(currentIndex);
    }

    // Copied from {@link InputMethodInfo}. See how auxiliary of IME is determined.
    private static boolean isAuxiliaryIme(final InputMethodInfo imi) {
        final int count = imi.getSubtypeCount();
        if (count == 0) {
            return false;
        }
        for (int index = 0; index < count; index++) {
            final InputMethodSubtype subtype = imi.getSubtypeAt(index);
            if (!subtype.isAuxiliary()) {
                return false;
            }
        }
        return true;
    }

    private static class InputMethodInfoCache {
        private final InputMethodManager mImm;
        private final String mImePackageName;

        private InputMethodInfo mCachedValue;

        public InputMethodInfoCache(final InputMethodManager imm, final String imePackageName) {
            mImm = imm;
            mImePackageName = imePackageName;
        }

        public synchronized InputMethodInfo get() {
            if (mCachedValue != null) {
                return mCachedValue;
            }
            for (final InputMethodInfo imi : mImm.getInputMethodList()) {
                if (imi.getPackageName().equals(mImePackageName)) {
                    mCachedValue = imi;
                    return imi;
                }
            }
            throw new RuntimeException("Input method id for " + mImePackageName + " not found.");
        }

        public synchronized void clear() {
            mCachedValue = null;
        }
    }

    public InputMethodInfo getInputMethodInfoOfThisIme() {
        return mInputMethodInfoCache.get();
    }

    public String getInputMethodIdOfThisIme() {
        return getInputMethodInfoOfThisIme().getId();
    }

    public boolean checkIfSubtypeBelongsToThisImeAndEnabled(final InputMethodSubtype subtype) {
        return checkIfSubtypeBelongsToImeAndEnabled(getInputMethodInfoOfThisIme(), subtype);
    }

    public boolean checkIfSubtypeBelongsToThisImeAndImplicitlyEnabled(
            final InputMethodSubtype subtype) {
        final boolean subtypeEnabled = checkIfSubtypeBelongsToThisImeAndEnabled(subtype);
        final boolean subtypeExplicitlyEnabled = checkIfSubtypeBelongsToList(
                subtype, getMyEnabledInputMethodSubtypeList(
                        false /* allowsImplicitlySelectedSubtypes */));
        return subtypeEnabled && !subtypeExplicitlyEnabled;
    }

    public boolean checkIfSubtypeBelongsToImeAndEnabled(final InputMethodInfo imi,
            final InputMethodSubtype subtype) {
        return checkIfSubtypeBelongsToList(subtype, getEnabledInputMethodSubtypeList(imi,
                true /* allowsImplicitlySelectedSubtypes */));
    }

    private static boolean checkIfSubtypeBelongsToList(final InputMethodSubtype subtype,
            final List<InputMethodSubtype> subtypes) {
        return getSubtypeIndexInList(subtype, subtypes) != INDEX_NOT_FOUND;
    }

    private static int getSubtypeIndexInList(final InputMethodSubtype subtype,
            final List<InputMethodSubtype> subtypes) {
        final int count = subtypes.size();
        for (int index = 0; index < count; index++) {
            final InputMethodSubtype ims = subtypes.get(index);
            if (ims.equals(subtype)) {
                return index;
            }
        }
        return INDEX_NOT_FOUND;
    }

    public boolean checkIfSubtypeBelongsToThisIme(final InputMethodSubtype subtype) {
        return getSubtypeIndexInIme(subtype, getInputMethodInfoOfThisIme()) != INDEX_NOT_FOUND;
    }

    private static int getSubtypeIndexInIme(final InputMethodSubtype subtype,
            final InputMethodInfo imi) {
        final int count = imi.getSubtypeCount();
        for (int index = 0; index < count; index++) {
            final InputMethodSubtype ims = imi.getSubtypeAt(index);
            if (ims.equals(subtype)) {
                return index;
            }
        }
        return INDEX_NOT_FOUND;
    }

    @Nonnull
    public RichInputMethodSubtype onSubtypeChanged(@Nonnull final InputMethodSubtype newSubtype) {
        final RichInputMethodSubtype richSubtype = createCurrentRichInputMethodSubtype(newSubtype);
        if (DEBUG) {
            Log.w(TAG, "onSubtypeChanged: " + richSubtype.getNameForLogging());
        }
        mCurrentRichInputMethodSubtype = richSubtype;
        return richSubtype;
    }

    private static RichInputMethodSubtype sForcedSubtypeForTesting = null;

    @UsedForTesting
    static void forceSubtype(final InputMethodSubtype subtype) {
        sForcedSubtypeForTesting = new RichInputMethodSubtype(subtype);
    }

    public Locale[] getCurrentSubtypeLocales() {
        if (null != sForcedSubtypeForTesting) {
            return sForcedSubtypeForTesting.getLocales();
        }
        return getCurrentSubtype().getLocales();
    }

    public RichInputMethodSubtype getCurrentSubtype() {
        if (null != sForcedSubtypeForTesting) {
            return sForcedSubtypeForTesting;
        }
        return mCurrentRichInputMethodSubtype;
    }


    public String getCombiningRulesExtraValueOfCurrentSubtype() {
        return SubtypeLocaleUtils.getCombiningRulesExtraValue(getCurrentSubtype().getRawSubtype());
    }

    @Nonnull
    public InputMethodSubtype getCurrentRawSubtype() {
        return mImmWrapper.mImm.getCurrentInputMethodSubtype();
    }

    @Nonnull
    public RichInputMethodSubtype createCurrentRichInputMethodSubtype(
            @Nonnull final InputMethodSubtype rawSubtype) {
        return AdditionalFeaturesSettingUtils.createRichInputMethodSubtype(this, rawSubtype,
                mContext);
    }

    public boolean hasMultipleEnabledIMEsOrSubtypes(final boolean shouldIncludeAuxiliarySubtypes) {
        final List<InputMethodInfo> enabledImis = mImmWrapper.mImm.getEnabledInputMethodList();
        return hasMultipleEnabledSubtypes(shouldIncludeAuxiliarySubtypes, enabledImis);
    }

    public boolean hasMultipleEnabledSubtypesInThisIme(
            final boolean shouldIncludeAuxiliarySubtypes) {
        final List<InputMethodInfo> imiList = Collections.singletonList(
                getInputMethodInfoOfThisIme());
        return hasMultipleEnabledSubtypes(shouldIncludeAuxiliarySubtypes, imiList);
    }

    private boolean hasMultipleEnabledSubtypes(final boolean shouldIncludeAuxiliarySubtypes,
            final List<InputMethodInfo> imiList) {
        // Number of the filtered IMEs
        int filteredImisCount = 0;

        for (InputMethodInfo imi : imiList) {
            // We can return true immediately after we find two or more filtered IMEs.
            if (filteredImisCount > 1) return true;
            final List<InputMethodSubtype> subtypes = getEnabledInputMethodSubtypeList(imi, true);
            // IMEs that have no subtypes should be counted.
            if (subtypes.isEmpty()) {
                ++filteredImisCount;
                continue;
            }

            int auxCount = 0;
            for (InputMethodSubtype subtype : subtypes) {
                if (subtype.isAuxiliary()) {
                    ++auxCount;
                }
            }
            final int nonAuxCount = subtypes.size() - auxCount;

            // IMEs that have one or more non-auxiliary subtypes should be counted.
            // If shouldIncludeAuxiliarySubtypes is true, IMEs that have two or more auxiliary
            // subtypes should be counted as well.
            if (nonAuxCount > 0 || (shouldIncludeAuxiliarySubtypes && auxCount > 1)) {
                ++filteredImisCount;
                continue;
            }
        }

        if (filteredImisCount > 1) {
            return true;
        }
        final List<InputMethodSubtype> subtypes = getMyEnabledInputMethodSubtypeList(true);
        int keyboardCount = 0;
        // imm.getEnabledInputMethodSubtypeList(null, true) will return the current IME's
        // both explicitly and implicitly enabled input method subtype.
        // (The current IME should be LatinIME.)
        for (InputMethodSubtype subtype : subtypes) {
            if (KEYBOARD_MODE.equals(subtype.getMode())) {
                ++keyboardCount;
            }
        }
        return keyboardCount > 1;
    }

    public InputMethodSubtype findSubtypeByLocaleAndKeyboardLayoutSet(final String localeString,
            final String keyboardLayoutSetName) {
        final InputMethodInfo myImi = getInputMethodInfoOfThisIme();
        final int count = myImi.getSubtypeCount();
        for (int i = 0; i < count; i++) {
            final InputMethodSubtype subtype = myImi.getSubtypeAt(i);
            final String layoutName = SubtypeLocaleUtils.getKeyboardLayoutSetName(subtype);
            if (localeString.equals(subtype.getLocale())
                    && keyboardLayoutSetName.equals(layoutName)) {
                return subtype;
            }
        }
        return null;
    }

    public void setInputMethodAndSubtype(final IBinder token, final InputMethodSubtype subtype) {
        mImmWrapper.mImm.setInputMethodAndSubtype(
                token, getInputMethodIdOfThisIme(), subtype);
    }

    public void setAdditionalInputMethodSubtypes(final InputMethodSubtype[] subtypes) {
        mImmWrapper.mImm.setAdditionalInputMethodSubtypes(
                getInputMethodIdOfThisIme(), subtypes);
        // Clear the cache so that we go read the {@link InputMethodInfo} of this IME and list of
        // subtypes again next time.
        clearSubtypeCaches();
    }

    private List<InputMethodSubtype> getEnabledInputMethodSubtypeList(final InputMethodInfo imi,
            final boolean allowsImplicitlySelectedSubtypes) {
        final HashMap<InputMethodInfo, List<InputMethodSubtype>> cache =
                allowsImplicitlySelectedSubtypes
                ? mSubtypeListCacheWithImplicitlySelectedSubtypes
                : mSubtypeListCacheWithoutImplicitlySelectedSubtypes;
        final List<InputMethodSubtype> cachedList = cache.get(imi);
        if (null != cachedList) return cachedList;
        final List<InputMethodSubtype> result = mImmWrapper.mImm.getEnabledInputMethodSubtypeList(
                imi, allowsImplicitlySelectedSubtypes);
        cache.put(imi, result);
        return result;
    }

    public void clearSubtypeCaches() {
        mSubtypeListCacheWithImplicitlySelectedSubtypes.clear();
        mSubtypeListCacheWithoutImplicitlySelectedSubtypes.clear();
        mInputMethodInfoCache.clear();
    }

    public boolean shouldOfferSwitchingToNextInputMethod(final IBinder binder,
            boolean defaultValue) {
        // Use the default value instead on Jelly Bean MR2 and previous where
        // {@link InputMethodManager#shouldOfferSwitchingToNextInputMethod} isn't yet available
        // and on KitKat where the API is still just a stub to return true always.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT) {
            return defaultValue;
        }
        return mImmWrapper.shouldOfferSwitchingToNextInputMethod(binder);
    }

    public boolean isSystemLocaleSameAsLocaleOfAllEnabledSubtypesOfEnabledImes() {
        final Locale systemLocale = mContext.getResources().getConfiguration().locale;
        final Set<InputMethodSubtype> enabledSubtypesOfEnabledImes = new HashSet<>();
        final InputMethodManager inputMethodManager = getInputMethodManager();
        final List<InputMethodInfo> enabledInputMethodInfoList =
                inputMethodManager.getEnabledInputMethodList();
        for (final InputMethodInfo info : enabledInputMethodInfoList) {
            final List<InputMethodSubtype> enabledSubtypes =
                    inputMethodManager.getEnabledInputMethodSubtypeList(
                            info, true /* allowsImplicitlySelectedSubtypes */);
            if (enabledSubtypes.isEmpty()) {
                // An IME with no subtypes is found.
                return false;
            }
            enabledSubtypesOfEnabledImes.addAll(enabledSubtypes);
        }
        for (final InputMethodSubtype subtype : enabledSubtypesOfEnabledImes) {
            if (!subtype.isAuxiliary() && !subtype.getLocale().isEmpty()
                    && !systemLocale.equals(SubtypeLocaleUtils.getSubtypeLocale(subtype))) {
                return false;
            }
        }
        return true;
    }

    // TODO: Make this private
    void updateShortcutIME() {
        if (DEBUG) {
            Log.d(TAG, "Update shortcut IME from : "
                    + (mShortcutInputMethodInfo == null
                            ? "<null>" : mShortcutInputMethodInfo.getId()) + ", "
                    + (mShortcutSubtype == null ? "<null>" : (
                            mShortcutSubtype.getLocale() + ", " + mShortcutSubtype.getMode())));
        }
        // TODO: Update an icon for shortcut IME
        final Map<InputMethodInfo, List<InputMethodSubtype>> shortcuts =
                getInputMethodManager().getShortcutInputMethodsAndSubtypes();
        mShortcutInputMethodInfo = null;
        mShortcutSubtype = null;
        for (final InputMethodInfo imi : shortcuts.keySet()) {
            final List<InputMethodSubtype> subtypes = shortcuts.get(imi);
            // TODO: Returns the first found IMI for now. Should handle all shortcuts as
            // appropriate.
            mShortcutInputMethodInfo = imi;
            // TODO: Pick up the first found subtype for now. Should handle all subtypes
            // as appropriate.
            mShortcutSubtype = subtypes.size() > 0 ? subtypes.get(0) : null;
            break;
        }
        if (DEBUG) {
            Log.d(TAG, "Update shortcut IME to : "
                    + (mShortcutInputMethodInfo == null
                            ? "<null>" : mShortcutInputMethodInfo.getId()) + ", "
                    + (mShortcutSubtype == null ? "<null>" : (
                            mShortcutSubtype.getLocale() + ", " + mShortcutSubtype.getMode())));
        }
    }

    public void switchToShortcutIME(final InputMethodService context) {
        if (mShortcutInputMethodInfo == null) {
            return;
        }

        final String imiId = mShortcutInputMethodInfo.getId();
        switchToTargetIME(imiId, mShortcutSubtype, context);
    }

    private void switchToTargetIME(final String imiId, final InputMethodSubtype subtype,
            final InputMethodService context) {
        final IBinder token = context.getWindow().getWindow().getAttributes().token;
        if (token == null) {
            return;
        }
        final InputMethodManager imm = getInputMethodManager();
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                imm.setInputMethodAndSubtype(token, imiId, subtype);
                return null;
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    public boolean isShortcutImeEnabled() {
        updateShortcutIME();
        if (mShortcutInputMethodInfo == null) {
            return false;
        }
        if (mShortcutSubtype == null) {
            return true;
        }
        return checkIfSubtypeBelongsToImeAndEnabled(
                mShortcutInputMethodInfo, mShortcutSubtype);
    }

    public boolean isShortcutImeReady() {
        updateShortcutIME();
        if (mShortcutInputMethodInfo == null) {
            return false;
        }
        if (mShortcutSubtype == null) {
            return true;
        }
        if (mShortcutSubtype.containsExtraValueKey(REQ_NETWORK_CONNECTIVITY)) {
            return mIsNetworkConnected;
        }
        return true;
    }

    public void onNetworkStateChanged(final Intent intent) {
        final boolean noConnection = intent.getBooleanExtra(
                ConnectivityManager.EXTRA_NO_CONNECTIVITY, false);
        mIsNetworkConnected = !noConnection;

        KeyboardSwitcher.getInstance().onNetworkStateChanged();
    }
}
