import fs from 'node:fs';
import path from 'node:path';

const root = process.cwd();
const appDir = path.join(root, 'android', 'app');
const javaRoot = path.join(appDir, 'src', 'main', 'java');
const packageDir = path.join(javaRoot, 'com', 'montanamusic', 'app');
fs.mkdirSync(packageDir, { recursive: true });

for (const file of ['PlaybackService.java', 'NativePlaybackPlugin.java']) {
  fs.copyFileSync(path.join(root, 'android-native', file), path.join(packageDir, file));
}

const manifestPath = path.join(appDir, 'src', 'main', 'AndroidManifest.xml');
let manifest = fs.readFileSync(manifestPath, 'utf8');
const permissions = [
  'android.permission.FOREGROUND_SERVICE',
  'android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK',
  'android.permission.WAKE_LOCK'
];
for (const permission of permissions) {
  const line = `    <uses-permission android:name="${permission}" />`;
  if (!manifest.includes(`android:name="${permission}"`)) {
    manifest = manifest.replace('<application', line + '\\n    <application');
  }
}
if (!manifest.includes('com.montanamusic.app.PlaybackService')) {
  manifest = manifest.replace('</application>', `        <service
            android:name="com.montanamusic.app.PlaybackService"
            android:foregroundServiceType="mediaPlayback"
            android:exported="true">
            <intent-filter>
                <action android:name="androidx.media3.session.MediaSessionService" />
            </intent-filter>
        </service>
    </application>`);
}
fs.writeFileSync(manifestPath, manifest);

const gradlePath = path.join(appDir, 'build.gradle');
let gradle = fs.readFileSync(gradlePath, 'utf8');
if (!gradle.includes('androidx.media3:media3-exoplayer')) {
  gradle += `\\n\\ndependencies {\\n    implementation "androidx.media3:media3-exoplayer:1.11.1"\\n    implementation "androidx.media3:media3-session:1.11.1"\\n}\\n`;
}
fs.writeFileSync(gradlePath, gradle);

const mainActivity = [];
function walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full);
    else if (entry.name === 'MainActivity.java') mainActivity.push(full);
  }
}
walk(javaRoot);
if (mainActivity.length !== 1) throw new Error(`Se esperaba un MainActivity.java y se encontraron ${mainActivity.length}`);
let activity = fs.readFileSync(mainActivity[0], 'utf8');
if (!activity.includes('NativePlaybackPlugin.class')) {
  activity = activity.replace(/(package [^;]+;)/, '$1\\n\\nimport com.montanamusic.app.NativePlaybackPlugin;');
  activity = activity.replace(/super\\.onCreate\\(savedInstanceState\\);/, 'registerPlugin(NativePlaybackPlugin.class);\\n        super.onCreate(savedInstanceState);');
  if (!activity.includes('NativePlaybackPlugin.class')) throw new Error('No se pudo registrar NativePlaybackPlugin en MainActivity.');
}
fs.writeFileSync(mainActivity[0], activity);
console.log('Integración Media3 aplicada al proyecto Android generado.');
