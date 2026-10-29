package io.auralis.desktop;
public final class Launcher {
    private Launcher() {}
    public static void main(String[] args) {
        io.auralis.core.util.NetworkRuntime.prepare();
        if(java.util.Arrays.asList(args).contains("--migrate-data")){
            try{System.out.println("AURALIS_DATA_MIGRATION_OK "+AuralisApp.prepareDataDirectory());}
            catch(Exception error){System.err.println("Перенос данных остановлен: "+error.getMessage());System.exit(1);}
            return;
        }
        try{AuralisApp.prepareDataDirectory();}
        catch(Exception error){
            System.err.println("Перенос данных остановлен: "+error.getMessage());
            javafx.application.Platform.startup(()->{var alert=new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR);alert.setTitle("Auralis — сохранение медиатеки");alert.setHeaderText("Не удалось безопасно подготовить данные");alert.setContentText(error.getMessage()+"\n\nЗакройте предыдущую версию и выполните Safe-Update.ps1. Старая медиатека не удалена.");alert.showAndWait();javafx.application.Platform.exit();});
            return;
        }
        AuralisApp.main(args);
    }
}
