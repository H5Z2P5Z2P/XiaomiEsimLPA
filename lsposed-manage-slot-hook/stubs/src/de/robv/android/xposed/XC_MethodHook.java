package de.robv.android.xposed;

import java.lang.reflect.Member;

public abstract class XC_MethodHook {
    public static class Unhook {
    }

    public static class MethodHookParam {
        public Object thisObject;
        public Object[] args;
        public Member method;
        private Object result;

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
        }
    }
}
