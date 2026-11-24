package jp.nano.rawgallery.camera;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exercises registration migration and mount selection without touching app settings or the filesystem of a card. */
public final class SdSourceRegistrationChecks {
    private static final String PROVIDER = "com.android.externalstorage.documents";
    private static final String PREFS = "camera_import_sd_registry_checks";
    private static final String LATEST = "sd_tree_uri";
    private static final String NON_FAT = "11AABBCC-1234-5678-90AB-1234567890FF";
    private static final Uri A = tree("ABCD-1234:");
    private static final Uri A_DCIM = tree("ABCD1234:DCIM");
    private static final Uri B = tree("5678-DDEF:DCIM");
    private static final Uri C = tree(NON_FAT + ":DCIM");

    private SdSourceRegistrationChecks() {}

    public static void run(Context target) {
        SharedPreferences data = target.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        AtomicBoolean failCommit = new AtomicBoolean();
        SharedPreferences preferences = proxy(SharedPreferences.class, data, (method, result) ->
                "edit".equals(method) ? editor((SharedPreferences.Editor) result, failCommit) : result);
        Context context = new ContextWrapper(target) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                if (!"camera_import".equals(name)) throw new AssertionError("Unexpected preferences");
                return preferences;
            }
        };
        try {
            require(data.edit().clear().putString(LATEST, A.toString()).commit(), "legacy fixture");
            require(A.equals(SdSourceRegistrations.forVolume(context, "abcd1234")), "read legacy volume-root tree");
            require(SdSourceRegistrations.registrations(context).equals(List.of(A)), "legacy tree exposed before any write");
            require("abcd-1234".equals(SdSourceRegistrations.volumeUuid(A_DCIM)), "normalize dashless FAT UUID");
            require(NON_FAT.toLowerCase(Locale.ROOT).equals(SdSourceRegistrations.volumeUuid(C)), "full filesystem UUID");
            require(SdSourceRegistrations.volumeUuid(tree("primary:DCIM")) == null, "primary is not a removable UUID");
            require(SdSourceRegistrations.volumeUuid(tree("ABCD-1234")) == null, "missing document separator");
            require(SdSourceRegistrations.volumeUuid(Uri.parse(A + "?spoofed=1")) == null, "reject altered tree URI");
            require(SdSourceRegistrations.volumeUuid(DocumentsContract.buildTreeDocumentUri("other.provider", "ABCD-1234:DCIM")) == null,
                    "ignore UUID-shaped IDs from other providers");
            Map<String, ?> before = data.getAll();
            failCommit.set(true);
            require(!SdSourceRegistrations.save(context, B) && data.getAll().equals(before), "failed migration restores old settings");
            require(SdSourceRegistrations.save(context, B), "save second card");
            require(B.toString().equals(SdSourceRegistrations.latest(context)), "latest picker guard updated");
            require(A.equals(SdSourceRegistrations.forVolume(context, "abcd-1234"))
                    && B.equals(SdSourceRegistrations.forVolume(context, "5678ddef")), "both card folders survived migration");
            require(!SdSourceRegistrations.save(context, A), "existing volume-root tree is not valid for a new selection");
            require(SdSourceRegistrations.save(context, A_DCIM), "replace only the folder of the selected volume");
            require(!SdSourceRegistrations.contains(context, A) && SdSourceRegistrations.contains(context, A_DCIM)
                    && SdSourceRegistrations.contains(context, B), "membership follows the exact current tree per UUID");
            require(SdSourceRegistrations.save(context, C) && SdSourceRegistrations.contains(context, C), "third distinct card");
            before = data.getAll();
            failCommit.set(true);
            require(!SdSourceRegistrations.save(context, tree("5678-DDEF:DCIM/Other")) && data.getAll().equals(before),
                    "failed replacement restores the old row and latest picker guard");
            mountSelection(SdSourceRegistrations.registrations(context));
            Uri legacy = tree("primary:DCIM");
            require(data.edit().clear().putString(LATEST, legacy.toString()).commit(), "non-UUID legacy fixture");
            require(SdSourceRegistrations.save(context, B) && SdSourceRegistrations.contains(context, legacy),
                    "older manually usable tree survives new removable registration");
            List<Uri> registered = SdSourceRegistrations.registrations(context);
            require(!SdSourceRegistrations.hasReadableRegistration(registered, legacy::equals),
                    "a grant without identifiable UUID cannot enable mount-triggered importing");
            require(!SdSourceRegistrations.hasReadableRegistration(registered, missing -> false), "a UUID without grant cannot enable auto");
            require(SdSourceRegistrations.hasReadableRegistration(registered, B::equals), "a granted UUID can enable auto");
            Uri altered = A_DCIM.buildUpon().appendQueryParameter("spoofed", "1").build();
            require(data.edit().putString(LATEST, altered.toString()).commit(), "altered URI fixture");
            require(!SdSourceRegistrations.contains(context, altered) && SdSourceRegistrations.contains(context, legacy),
                    "an altered URI cannot replace the compatible fallback");
            require(data.edit().clear().putString(LATEST, altered.toString()).commit(), "altered URI migration fixture");
            require(SdSourceRegistrations.registrations(context).isEmpty() && SdSourceRegistrations.save(context, B)
                    && SdSourceRegistrations.registrations(context).equals(List.of(B)), "do not retain an altered URI on migration");
            Uri missingSeparator = tree("ABCD-1234");
            require(data.edit().clear().putString(LATEST, missingSeparator.toString()).commit(), "malformed document fixture");
            require(SdSourceRegistrations.registrations(context).isEmpty() && SdSourceRegistrations.save(context, B)
                    && SdSourceRegistrations.registrations(context).equals(List.of(B)), "reject a malformed platform ID before migration");
            Uri otherProvider = DocumentsContract.buildTreeDocumentUri("other.provider", "dcim-directory");
            require(data.edit().clear().putString(LATEST, otherProvider.toString()).commit(), "opaque document fixture");
            require(SdSourceRegistrations.save(context, B) && SdSourceRegistrations.contains(context, otherProvider),
                    "preserve valid opaque IDs for manual use");
        } finally {
            target.deleteSharedPreferences(PREFS);
        }
    }

    private static void mountSelection(List<Uri> registered) {
        var a = new SdSourceRegistrations.Volume("abcd-1234", "abcd-1234", true, true);
        var b = new SdSourceRegistrations.Volume("5678-ddef", "5678-ddef", true, true);
        var c = new SdSourceRegistrations.Volume(SdSourceRegistrations.volumeUuid(C), "external_c", true, false);
        var unregistered = new SdSourceRegistrations.Volume("1234-abcd", "1234-abcd", true, true);
        require(SdSourceRegistrations.mountedSources(registered, List.of(a, c, unregistered), "external_primary").trees().equals(List.of(A_DCIM)),
                "returning card recognized independently of latest selection");
        require(SdSourceRegistrations.mountedSources(registered, List.of(b), "external_primary").trees().equals(List.of(B)),
                "swap to another registered card without a new folder selection");
        require(SdSourceRegistrations.mountedSources(registered, List.of(a), "external_primary").trees().equals(List.of(A_DCIM)),
                "swap back to the first registered card");
        var excludingDestination = SdSourceRegistrations.mountedSources(registered, List.of(a, b), "ABCD1234");
        require(excludingDestination.trees().equals(List.of(B)) && A_DCIM.equals(excludingDestination.destinationTree()),
                "exclude destination aliases and distinguish same-card state");
        var ambiguous = SdSourceRegistrations.mountedSources(registered, List.of(a, b), "external_primary");
        require(ambiguous.trees().containsAll(List.of(A_DCIM, B)) && ambiguous.unambiguousTree() == null,
                "a newly mounted second registered card invalidates an earlier implicit selection");
        require(B.equals(excludingDestination.unambiguousTree()), "the selected destination does not invalidate a distinct source");
        Uri legacy = tree("primary:DCIM");
        var withLegacy = List.of(A_DCIM, B, legacy);
        require(legacy.equals(SdSourceRegistrations.mountedSources(withLegacy, List.of(), "external_primary").unambiguousTree()),
                "legacy selection remains available when no registered removable source is mounted");
        require(A_DCIM.equals(SdSourceRegistrations.mountedSources(withLegacy, List.of(a), "external_primary").unambiguousTree()),
                "a recognized removable candidate takes precedence over the legacy fallback");
        require(SdSourceRegistrations.mountedSources(withLegacy, List.of(a, b), "external_primary").unambiguousTree() == null,
                "legacy fallback cannot bypass multiple mounted sources");
        var duplicate = new SdSourceRegistrations.Volume("abcd-1234", "duplicate_external", true, false);
        require(SdSourceRegistrations.mountedSources(registered, List.of(a, duplicate), "external_primary").trees().isEmpty(), "exclude duplicate UUID");
        var duplicateName = new SdSourceRegistrations.Volume("eeee-ffff", "abcd-1234", true, true);
        require(SdSourceRegistrations.mountedSources(registered, List.of(a, duplicateName), "external_primary").trees().isEmpty(),
                "exclude ambiguous MediaStore volume");
    }

    private static Uri tree(String id) {
        return DocumentsContract.buildTreeDocumentUri(PROVIDER, id);
    }

    private static SharedPreferences.Editor editor(SharedPreferences.Editor delegate, AtomicBoolean failCommit) {
        return (SharedPreferences.Editor) Proxy.newProxyInstance(SdSourceRegistrationChecks.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                    if ("commit".equals(method.getName()) && failCommit.getAndSet(false)) {
                        delegate.apply();
                        return false;
                    }
                    Object result = invoke(method, delegate, args);
                    return result == delegate ? proxy : result;
                });
    }

    private static <T> T proxy(Class<T> type, T delegate, java.util.function.BiFunction<String, Object, Object> intercept) {
        return type.cast(Proxy.newProxyInstance(SdSourceRegistrationChecks.class.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) ->
                intercept.apply(method.getName(), invoke(method, delegate, args))));
    }

    private static Object invoke(java.lang.reflect.Method method, Object delegate, Object[] args) throws Throwable {
        try { return method.invoke(delegate, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
