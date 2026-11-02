# Encrypted backup of the MercedesDoctor release signing key

`MercedesDoctor-release-key-backup.tar.gz.age` is encrypted using age v1.2.1 to the recipient's **new** public key. Only ciphertext is published; the keystore and its password are not in Git. Decryption requires the matching Windows identity file `backup-key.txt`. The older public key is not a recipient.

This backup contains `m274-release.p12` and `storepass`. The release signing certificate SHA256 fingerprint is:

`965e1fef3d5ce843b8339c59fb028b42a8abeac1d9d71ca37bcc08b87abc9b9d`

Keep both the encrypted archive and your Windows identity file safe, preferably with separate protected backup copies. Keep the identity file private. The agent does not have it and cannot verify decryption for you.

## Verify and decrypt on Windows

Download the `.age` file. In PowerShell, opened in the directory containing `age.exe`, run:

```powershell
$keyFolder = "$env:USERPROFILE\MercedesDoctor-key-backup"
$archive = "$env:USERPROFILE\Downloads\MercedesDoctor-release-key-backup.tar.gz.age"
.\age.exe --decrypt --identity "$keyFolder\backup-key.txt" --output "$keyFolder\release-key-backup.tar.gz" $archive
if ($LASTEXITCODE -eq 0) { tar -tzf "$keyFolder\release-key-backup.tar.gz" }
```

If your browser uses another download folder, adjust `$archive`. Successful decryption and listing must show exactly `m274-release.p12` and `storepass`. These names are safe to report; do not share either file's contents. The decrypted archive includes the private signing key and its password: store it privately, never upload it to GitHub. It is not necessary to extract it for normal app use.

A SHA256 checksum of the encrypted download is provided in `SHA256SUMS.txt`.
