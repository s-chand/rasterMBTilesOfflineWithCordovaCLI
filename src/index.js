var UTILS = require('./utils');


$(document).ready(function () {
    document.addEventListener("deviceready", onDeviceReady, true); // this gets called once phonegap/cordova api is running
});
function onDeviceReady() {

    window.sqlitePlugin.echoTest(successCallback, errorCallback);
    function successCallback() {
        UTILS.logIt('successCallback')
    }
    function errorCallback() {
        UTILS.logIt('errorCallback')
    }
   // var db = window.sqlitePlugin.openDatabase({name: "Tiles.db", location: 'default', createFromLocation: 1});

}