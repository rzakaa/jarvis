#!/system/bin/sh
# Dodaje certyfikat (np. antywirusa Norton, który podmienia HTTPS na komputerze) do systemowych
# certyfikatów emulatora Android 14+ (APEX conscrypt). Działa do restartu emulatora. Wymaga adb root.
# Użycie: sh ca-systemowy.sh /data/local/tmp/<hash>.0
CERT="$1"
set -e
mkdir -p -m 700 /data/local/tmp/ca-kopia
cp /apex/com.android.conscrypt/cacerts/* /data/local/tmp/ca-kopia/
mount -t tmpfs tmpfs /system/etc/security/cacerts
mv /data/local/tmp/ca-kopia/* /system/etc/security/cacerts/
cp "$CERT" /system/etc/security/cacerts/
chown root:root /system/etc/security/cacerts/*
chmod 644 /system/etc/security/cacerts/*
chcon u:object_r:system_file:s0 /system/etc/security/cacerts/*
rmdir /data/local/tmp/ca-kopia

# Podmiana katalogu w przestrzeniach montowania zygote i wszystkich działających aplikacji.
ZYGOTES="$(pidof zygote || true) $(pidof zygote64 || true)"
for Z in $ZYGOTES; do
    nsenter --mount=/proc/$Z/ns/mnt -- /bin/mount --bind /system/etc/security/cacerts /apex/com.android.conscrypt/cacerts
done
for Z in $ZYGOTES; do
    for P in $(ps -o PID -P $Z | grep -v PID); do
        nsenter --mount=/proc/$P/ns/mnt -- /bin/mount --bind /system/etc/security/cacerts /apex/com.android.conscrypt/cacerts 2>/dev/null &
    done
done
wait
echo "CA OK"
