package com.hook.test;

import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookZygoteInit, IXposedHookLoadPackage {
    
    private static final String TAG = "SukisuUltraHook";
    private static final String MT_PACKAGE = "bin.mt.plus.canary";
    private static final String ULTRA_PACKAGE = "com.sukisu.ultra";
    
    // MT管理器的UID
    private static final int MT_UID = 10408;
    
    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        XposedBridge.log("[" + TAG + "] Zygote init started");
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        String packageName = lpparam.packageName;
        
        if (packageName.equals(ULTRA_PACKAGE)) {
            XposedBridge.log("[" + TAG + "] Hooking Sukisu Ultra: " + packageName);
            hookUltraManager(lpparam);
        }
    }
    
    private void hookUltraManager(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> nativesClass = XposedHelpers.findClass(
                "com.sukisu.ultra.Natives", 
                lpparam.classLoader
            );
            
            Class<?> profileClass = XposedHelpers.findClass(
                "com.sukisu.ultra.Natives$Profile", 
                lpparam.classLoader
            );
            
            XposedHelpers.findAndHookMethod(nativesClass, "getAppProfile", 
                String.class, int.class, 
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        String key = (String) param.args[0];
                        int uid = (int) param.args[1];
                        
                        if (MT_PACKAGE.equals(key) || uid == MT_UID) {
                            XposedBridge.log("[" + TAG + "] Intercepting getAppProfile for: " + key + " uid: " + uid);
                            
                            Object profile = createRootProfile(profileClass, key, uid);
                            if (profile != null) {
                                param.setResult(profile);
                                XposedBridge.log("[" + TAG + "] Successfully returned root profile for MT");
                            }
                        }
                    }
                }
            );
            
            XposedHelpers.findAndHookMethod(nativesClass, "getAllowList", 
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        int[] originalList = (int[]) param.getResult();
                        
                        boolean contains = false;
                        for (int u : originalList) {
                            if (u == MT_UID) {
                                contains = true;
                                break;
                            }
                        }
                        
                        if (!contains) {
                            int[] newList = Arrays.copyOf(originalList, originalList.length + 1);
                            newList[newList.length - 1] = MT_UID;
                            param.setResult(newList);
                            XposedBridge.log("[" + TAG + "] Added MT UID " + MT_UID + " to allowList");
                        }
                    }
                }
            );
            
            Class<?> mainActivityClass = XposedHelpers.findClass(
                "com.sukisu.ultra.ui.MainActivity",
                lpparam.classLoader
            );
            
            XposedHelpers.findAndHookMethod(mainActivityClass, "onCreate",
                Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        XposedBridge.log("[" + TAG + "] Ultra Manager started, granting root to MT");
                        grantRootToMT(nativesClass, profileClass);
                    }
                }
            );
            
        } catch (Exception e) {
            XposedBridge.log("[" + TAG + "] Error hooking Ultra: " + e);
            e.printStackTrace();
        }
    }
    
    private Object createRootProfile(Class<?> profileClass, String packageName, int uid) {
        try {
            List<Integer> emptyList = new ArrayList<>();
            
            Constructor<?> constructor = profileClass.getConstructor(
                String.class,           // name
                int.class,              // currentUid
                boolean.class,          // allowSu
                boolean.class,          // rootUseDefault
                String.class,           // rootTemplate (nullable)
                int.class,              // uid
                int.class,              // gid
                List.class,             // groups (List<Integer>)
                List.class,             // capabilities (List<Integer>)
                String.class,           // context
                int.class,              // namespace
                boolean.class,          // nonRootUseDefault
                boolean.class,          // umountModules
                String.class            // rules
            );
            
            Object profile = constructor.newInstance(
                packageName,            // name
                uid,                    // currentUid
                true,                   // allowSu = true
                true,                   // rootUseDefault
                null,                   // rootTemplate
                0,                      // uid (ROOT_UID)
                0,                      // gid (ROOT_GID)
                emptyList,              // groups
                emptyList,              // capabilities
                "u:r:su:s0",            // context (KERNEL_SU_DOMAIN)
                0,                      // namespace (INHERITED = 0)
                true,                   // nonRootUseDefault
                false,                  // umountModules
                ""                      // rules
            );
            
            XposedBridge.log("[" + TAG + "] Created root Profile for: " + packageName);
            return profile;
            
        } catch (Exception e) {
            XposedBridge.log("[" + TAG + "] Error creating Profile: " + e);
            e.printStackTrace();
            return null;
        }
    }
    
    private void grantRootToMT(Class<?> nativesClass, Class<?> profileClass) {
        try {
            Object profile = createRootProfile(profileClass, MT_PACKAGE, MT_UID);
            if (profile == null) {
                XposedBridge.log("[" + TAG + "] Failed to create profile for MT");
                return;
            }
            
            Method setProfileMethod = nativesClass.getMethod("setAppProfile", profileClass);
            boolean result = (boolean) setProfileMethod.invoke(null, profile);
            XposedBridge.log("[" + TAG + "] Grant root to MT result: " + result);
            
        } catch (Exception e) {
            XposedBridge.log("[" + TAG + "] Error in grantRootToMT: " + e);
            e.printStackTrace();
        }
    }
}

