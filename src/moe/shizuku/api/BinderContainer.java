/*
 * moe.shizuku.api.BinderContainer —— 照抄自 dev.rikka.shizuku:provider:13.1.5 的同名类
 * (Apache-2.0, https://github.com/RikkaApps/Shizuku-API)。原样保留包名:
 * server 发来的 Bundle 里装的就是这个类,类名不同无法反序列化。
 * 剥离了 androidx.annotation(编译期注解,无运行时影响)。
 */
package moe.shizuku.api;

import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;



public class BinderContainer implements Parcelable {

    public IBinder binder;

    public BinderContainer(IBinder binder) {
        this.binder = binder;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStrongBinder(this.binder);
    }

    protected BinderContainer(Parcel in) {
        this.binder = in.readStrongBinder();
    }

    public static final Creator<BinderContainer> CREATOR = new Creator<BinderContainer>() {
        @Override
        public BinderContainer createFromParcel(Parcel source) {
            return new BinderContainer(source);
        }

        @Override
        public BinderContainer[] newArray(int size) {
            return new BinderContainer[size];
        }
    };
}
