/**
 * Utility Module for various functions
 */
// dependencies





// ##########################################################################

/**
 * Displays logs in a div. Temporary solution since console can't be used.
 * Must be called after DOM has loaded
 * @param msg
 */
function logIt (msg) {
    var statusDiv = $('#status');
    statusDiv.append(msg + '<br/>')
};


var persistentVar = {};

persistentVar.create = function (varName) {

    if (!localStorage[varName]) {
        localStorage[varName] = '';
     //   logIt('persistent var ' + varName + ' created');
        return true
    } else {
     //  logIt('persistent var ' + varName + ' already exists, not created');
        return false
    }
};

persistentVar.set  = function(varName, value) {
   //logIt('setPersistentVar called with varname ' + varName + ' and value ' + value);
    if (localStorage[varName]) {
        localStorage[varName] = value;
    } else {
       // logIt('savePersistentVar | could not save persistent var ' + varName
      //      + ' because it could not be found in storage')
    }
};

persistentVar.get = function (varName) {
    return localStorage[varName]
};

// exports
module.exports = {
    logIt: logIt,
    persistentVar: persistentVar
};