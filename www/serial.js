const serial = {
  requestPermission: function (opts, successCallback, errorCallback) {
    if (typeof opts === "function") {
      //user did not pass opts
      errorCallback = successCallback;
      successCallback = opts;
      opts = {};
    }
    cordova.exec(
      successCallback,
      errorCallback,
      "Serial",
      "requestPermission",
      [{ opts: opts }],
    );
  },
  open: function (opts, successCallback, errorCallback) {
    cordova.exec(successCallback, errorCallback, "Serial", "openSerial", [
      { opts: opts },
    ]);
  },
  write: function (data, successCallback, errorCallback) {
    cordova.exec(successCallback, errorCallback, "Serial", "writeSerial", [
      { data: data },
    ]);
  },
  close: function (successCallback, errorCallback) {
    cordova.exec(successCallback, errorCallback, "Serial", "closeSerial", []);
  },
  registerReadCallback: function (successCallback, errorCallback) {
    cordova.exec(
      successCallback,
      errorCallback,
      "Serial",
      "registerReadCallback",
      [],
    );
  },
};
module.exports = serial;