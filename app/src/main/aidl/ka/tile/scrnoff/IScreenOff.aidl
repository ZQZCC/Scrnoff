package ka.tile.scrnoff;

interface IScreenOff {
    const int STATE_OFF = 0;
    const int STATE_ON = 1;
    const int STATE_SPECIAL = 2;

    void setPowerMode(boolean turnOff);

    void updateNowScreenState(boolean isScreenOn);

    int getNowScreenState();

    void closeAndExit();

}
