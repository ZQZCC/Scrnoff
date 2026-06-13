package ka.tile.scrnoff

import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable

class BinderContainer(val binder: IBinder?) : Parcelable {
    private constructor(parcel: Parcel) : this(parcel.readStrongBinder())

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeStrongBinder(binder)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<BinderContainer> =
            object : Parcelable.Creator<BinderContainer> {
                override fun createFromParcel(parcel: Parcel): BinderContainer =
                    BinderContainer(parcel)

                override fun newArray(size: Int): Array<BinderContainer?> =
                    arrayOfNulls(size)
            }
    }
}
