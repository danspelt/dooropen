param(
    [Parameter(Mandatory = $true)]
    [string]$Name
)

$ErrorActionPreference = "Stop"

# Best-effort: set default playback + recording via PolicyConfig COM API.
# If this fails, COMPUTER mode still turns Bluetooth on — user can pick device in Sound settings.

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

[Guid("870af99c-171d-4b9e-af67-d105b02d7d2c"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IPolicyConfig {
    int Unused1(); int Unused2(); int Unused3(); int Unused4(); int Unused5();
    int Unused6(); int Unused7(); int Unused8(); int Unused9(); int Unused10();
    [PreserveSig] int SetDefaultEndpoint([MarshalAs(UnmanagedType.LPWStr)] string deviceId, int role);
}

[ComImport, Guid("870af99c-171d-4b9e-af67-d105b02d7d2c")]
public class PolicyConfigClient { }

[ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
public class MMDeviceEnumerator { }

[Guid("A95664D2-9614-4D50-A840-6E8D82CAFF00"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IMMDeviceEnumerator {
    int NotNeeded();
    int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice device);
    int EnumAudioEndpoints(int dataFlow, int stateMask, out IMMDeviceCollection devices);
}

[Guid("0BE50410-1A3C-11D2-B521-00C04FB66826"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IMMDeviceCollection {
    int GetCount(out uint count);
    int Item(uint index, out IMMDevice device);
}

[Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IMMDevice {
    int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
    int OpenPropertyStore(int access, out IPropertyStore props);
}

[Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IPropertyStore {
    int GetCount(out uint count);
    int GetAt(uint index, out PROPERTYKEY key);
    int GetValue(ref PROPERTYKEY key, out PropVariant value);
}

[StructLayout(LayoutKind.Sequential)]
public struct PROPERTYKEY {
    public Guid fmtid;
    public uint pid;
}

[StructLayout(LayoutKind.Explicit)]
public struct PropVariant {
    [FieldOffset(0)] public ushort vt;
    [FieldOffset(8)] public IntPtr ptr;
}

public static class AudioDefault {
    static PROPERTYKEY FriendlyName = new PROPERTYKEY {
        fmtid = new Guid("a45c254e-df1c-4efd-8020-67d146a850e0"), pid = 14
    };

    public static bool SetByName(string name) {
        var policy = (IPolicyConfig)new PolicyConfigClient();
        bool ok = false;
        ok |= SetFlow(policy, 0, name);
        ok |= SetFlow(policy, 1, name);
        return ok;
    }

    static bool SetFlow(IPolicyConfig policy, int flow, string name) {
        var enumr = (IMMDeviceEnumerator)new MMDeviceEnumerator();
        IMMDeviceCollection col;
        if (enumr.EnumAudioEndpoints(flow, 1, out col) != 0) return false;
        uint count;
        col.GetCount(out count);
        string bestId = null;
        for (uint i = 0; i < count; i++) {
            IMMDevice dev;
            if (col.Item(i, out dev) != 0) continue;
            string id;
            if (dev.GetId(out id) != 0) continue;
            IPropertyStore store;
            if (dev.OpenPropertyStore(0, out store) != 0) continue;
            PropVariant pv;
            var key = FriendlyName;
            if (store.GetValue(ref key, out pv) != 0) continue;
            if (pv.vt != 31) continue;
            var label = Marshal.PtrToStringUni(pv.ptr);
            if (label == null) continue;
            if (label.IndexOf(name, StringComparison.OrdinalIgnoreCase) >= 0) {
                bestId = id;
                if (label.Equals(name, StringComparison.OrdinalIgnoreCase)) break;
            }
        }
        if (bestId == null) return false;
        policy.SetDefaultEndpoint(bestId, 0);
        policy.SetDefaultEndpoint(bestId, 1);
        policy.SetDefaultEndpoint(bestId, 2);
        return true;
    }
}
'@ -ErrorAction Stop

try {
    if ([AudioDefault]::SetByName($Name)) {
        Write-Output "ok"
    } else {
        Write-Output "skipped"
    }
} catch {
    Write-Output "skipped"
}
