package ka.tile.scrnoff;

interface IScreenOff {
    const int STATE_OFF = 0;
    const int STATE_ON = 1;
    const int STATE_SPECIAL = 2;

    oneway void setPowerMode(boolean turnOff);

    oneway void updateNowScreenState(boolean isScreenOn);

    int getNowScreenState();

    oneway void closeAndExit();
}
