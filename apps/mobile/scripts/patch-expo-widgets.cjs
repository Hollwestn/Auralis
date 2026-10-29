const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..', 'node_modules', 'expo-widgets');
if (require(path.join(root, 'package.json')).version !== '58.0.25') throw new Error('Review Auralis widget patch for the installed Expo Widgets version.');
const file = path.join(root, 'android/src/main/java/expo/modules/widgets/WidgetInteractionAction.kt');
let source = fs.readFileSync(file, 'utf8');
if (!source.includes('// Auralis: explicit foreground widget controls')) {
  const anchor = 'internal fun WidgetInteraction.toGlanceAction(context: Context): Action {';
  if (!source.includes(anchor)) throw new Error('Expo Widgets action anchor changed.');
  source = source.replace(anchor, anchor + `
  // Auralis: explicit foreground widget controls, including cold app launches.
  if (source is InteractionSource.Widget && source.name == "AuralisNowPlaying" && target in setOf("auralis:previous", "auralis:toggle", "auralis:next")) {
    val command = target.substringAfter(":")
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
      ?: throw IllegalStateException("Auralis launch activity unavailable")
    launch.action = Intent.ACTION_VIEW
    launch.data = Uri.parse("auralis://widget?command=" + command)
    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    return androidx.glance.appwidget.action.actionStartActivity(launch)
  }
`);
  fs.writeFileSync(file, source);
}
console.log('Auralis: home-screen widget foreground controls patched.');
