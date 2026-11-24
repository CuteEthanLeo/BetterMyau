package myau.via.platform;

import com.viaversion.viabackwards.protocol.v1_21_6to1_21_5.Protocol1_21_6To1_21_5;
import com.viaversion.viabackwards.protocol.v1_21_6to1_21_5.data.Dialog;
import com.viaversion.viabackwards.protocol.v1_21_6to1_21_5.provider.ChestDialogViewProvider;
import com.viaversion.viaversion.api.connection.UserConnection;
import myau.via.ViaDialogs;

/**
 * Server dialogs (1.21.6+): while the server configures the player they are shown by the client
 * itself (see {@link ViaDialogs}); during play they stay ViaBackwards' chest window. A subclass of
 * the chest provider because ViaBackwards casts the registered provider to it.
 */
final class NativeDialogProvider extends ChestDialogViewProvider {
    NativeDialogProvider(Protocol1_21_6To1_21_5 protocol) {
        super(protocol);
    }

    @Override
    public void openDialog(UserConnection connection, Dialog dialog) {
        if (ViaDialogs.handles(connection)) ViaDialogs.open(connection, dialog);
        else super.openDialog(connection, dialog);
    }

    @Override
    public void closeDialog(UserConnection connection) {
        if (ViaDialogs.handles(connection)) ViaDialogs.clear(connection);
        else super.closeDialog(connection);
    }
}
