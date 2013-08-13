// ==UserScript==
// @name       Generate HS GK Report
// @namespace  https://github.com/jbachorik/aurora
// @version    0.1
// @description  Generates a pre-filled GK HS nightly report
// @match      http://aurora.ru.oracle.com/functional/faces/ChessBoard.xhtml?reportName=SQENightlyDatesFromBatches*
// @copyright  2013+, Jaroslav Bachorik
// ==/UserScript==

function newFailures(mydoc) {
    var failures = new Array()
    
    var f = mydoc.evaluate( "//span[@class='failures-unknown']", mydoc, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE , null )
    
    for (var i = 0;i < f.snapshotLength; i++) {
        var e = f.snapshotItem(i)
        var p = e.parentNode.parentNode.parentNode.parentNode.parentNode.parentNode
        if (p.nodeName == "a" || p.nodeName == "A") {
            failures.push(p)
        }
    }
    
    return failures
}

function getDetails(link) {
    incrementCountdown()
    
    GM_xmlhttpRequest({
        method: 'GET',
        url: link,
        onload: asyncDetailsHandler
    })
}

function parseFailures(link) {
    incrementCountdown()
    GM_xmlhttpRequest({
        method: 'GET',
        url: link,
        onload: asyncFailuresHandler
    })
}

function asyncDetailsHandler(resp) {
    var range = document.createRange();
    range.setStartAfter(document.body);
    var xhr_frag = range.createContextualFragment(resp.responseText);
    var doc = document.implementation.createDocument(null, 'details', null);
    doc.adoptNode(xhr_frag);
    doc.documentElement.appendChild(xhr_frag);
    
    var as = doc.getElementsByTagName("a")
    var fHrefs = []
    
    for(var i=0;i<as.length;i++) {
        var href = as[i].getAttribute("href")
        if (href !== undefined && href !== null &&
            href.indexOf("RunDetails.xhtml") > -1) {
            var spans = as[i].getElementsByTagName("span")
            
            for (var j = 0; j < spans.length; j++) {
                if (spans[j].getAttribute("class") == "failures-unknown") {
                    if (href in fHrefs) continue
                    
                    fHrefs.push(href)
                }
            }
        }
    }
    
    for (var i in fHrefs) {
        parseFailures("http://aurora.ru.oracle.com" + fHrefs[i])
    }
    decrementCountdown()
}

function asyncFailuresHandler(resp) {
    var range = document.createRange()
    range.setStartAfter(document.body)
    var xhr_frag = range.createContextualFragment(resp.responseText)
    var doc = document.implementation.createDocument(null, 'failures', null)
    
    doc.adoptNode(xhr_frag);
    doc.documentElement.appendChild(xhr_frag);
    
    var host, options
    
    var permlink = doc.getElementById("form:aurora-permanent-link").getAttribute("href")
    
    var tables = doc.getElementsByTagName("table")
    for(var i in tables) {
        if (tables[i].getAttribute !== undefined) {
            var clazz = tables[i].getAttribute("class");
            if (clazz !== undefined && clazz !== null && clazz.indexOf("run-table") > -1) {
                var details = tables[i].getElementsByTagName("tbody")[0].getElementsByTagName("tr")
                host = details[8].getElementsByTagName("td")[1].innerText
                options = details[25].getElementsByTagName("td")[1].innerText
                if (javaVersion === undefined) {
                    javaVersion = details[17].getElementsByTagName("td")[1].innerText
                    hsVersion = details[24].getElementsByTagName("td")[1].innerText
                }
            }
        }
    }
    
    var parentTable = doc.getElementById("form:j_idt88:j_idt415:j_idt421:j_idt422:j_idt427:table")
    var rows = parentTable.getElementsByTagName("tbody")[0].getElementsByTagName("tr")
    for(var i in rows) {
        if (rows[i].getElementsByTagName !== undefined) {
            var text = rows[i].getElementsByTagName("td")[0].innerText.trim()
            detailLines.push(text)
            detailLines.push("\n***\n")
            detailLines.push("Test run URL:\n" + "http://aurora.ru.oracle.com" + permlink + "\n")
            detailLines.push("Host: " + host.trim() + "\n")
            detailLines.push("Options: " + options.trim() + "\n")
            detailLines.push("\n***\n-----\n\n")
        }
    }
    
    decrementCountdown()
}

function parsingComplete() {
    var msg = "The " + current + " RT_Baseline nightly looks *** from a product bits POV:\n\n"
    msg += "   *** product failure(s)/regression(s)\n"
    msg += "   *** test bug/test config/DKFL mismatch(es)\n"
    msg += "   *** infra issue(s)\n\n"
    msg += "Nightly job info:\n\n"
    msg += "   " + jobStatus + "\n"
    msg += '   *** "new" failures; *** after updates\n'
    msg += "   JDK: " + javaVersion.trim() + "\n"
    msg += "   VM: " + hsVersion.trim() + "\n"
    msg += "   *** Test Totals:  " + totals + "\n\n"
    msg += "Gory details are below ...\n\n"
    msg += "***\n\n\n"
    msg += "Product Failures/Regressions\n"
    msg += "----------------------------\n\n"
    
    for(var i in detailLines) {
        msg += detailLines[i] + "\n"
    }
    
    msg += "\n\n"
    
    msg += "Test Bug/Test Config/DKFL Mismatches\n"
    msg += "------------------------------------\n\n"
    msg += "***\n\n"
    
    msg += "Infra Issues\n"
    msg += "------------\n\n"
    msg += "***"

    enable(document)  
    var win = unsafeWindow.open("about:blank", "Report", "width=800,height=600")
    win.document.write("<html><body><pre>" + msg + "</pre></body></html>")
}

var countdown = 0;
function incrementCountdown() {
    countdown += 1
}

function decrementCountdown() {
    countdown += -1
    if (countdown === 0) {
        parsingComplete()
    }
}

function parseStatus(doc) {
    var statusAnchor = doc.getElementById("form:j_idt83:j_idt111").
    getElementsByTagName("table")[0].
    getElementsByTagName("tbody")[0].
    getElementsByTagName("tr")[0].
    getElementsByTagName("td")[0].
    getElementsByTagName("a")[0]
    
    var title = statusAnchor.getAttribute("title").split(",")
    var text = statusAnchor.innerText.split("%")
    
    var part1 = text[1].replace("(", " ").replace(")", " ").trim().replace(" of ", "/")
    var part2 = text[0] + "%"
    var part3 = title[0].replace("failed", "jobs failed")
    
    jobStatus = part1 + " jobs completed (" + part2 + " complete, " + part3 + ")"
    
    //*[@id="form:j_idt83:j_idt111"]//table/tbody/tr[7]/td[3]/span/table/tbody/tr/td[1]/span
    
    var rslts = doc.evaluate("//*[@id='form:j_idt83:j_idt111']//table[@class = 'runResTable']/tbody/tr/td", doc, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE , null )
    totals = "new=" + rslts.snapshotItem(0).innerText.trim() + ", known=" + rslts.snapshotItem(1).innerText.trim() + ", passed=" + rslts.snapshotItem(2).innerText.trim()
}

function installReportHandler(doc) {
    var perlinkPath = doc.evaluate( "//a[text() = 'PermaLink']", doc, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE , null ).snapshotItem(0)
    
    var container = perlinkPath.parentNode.parentNode.getElementsByTagName("td")[0]
    
    var handler = doc.createElement("a")
    var handlerText = doc.createTextNode("Generate Report")
    handler.appendChild(handlerText)
    handler.href = "#"
    
    handler.addEventListener("click", generateReport)
    container.appendChild(handler)
}

function generateReport() {
    detailLines = []
    disable(document)
    current = document.getElementById("current_tab").getElementsByTagName("span")[0].innerText
    var failures = newFailures(document)
    
    parseStatus(document)
    
    for (var i in failures) {
        getDetails(failures[i].href)
    }
}

function disable(doc) {
    mouseStyle = doc.body.style.cursor
    doc.body.style.cursor = 'wait'
    div.style.display = ''
}

function enable(doc) {
    doc.body.style.cursor = mouseStyle
    div.style.display = 'none'
}

var javaVersion = undefined
var hsVersion = undefined
var jobStatus = undefined
var totals = undefined
var current = undefined
var mouseStyle = undefined

var detailLines = []

var div = document.createElement("div")
div.style.width = "100%"
div.style.height = "100%"
div.style.left = 0
div.style.right = 0
div.style.zIndex = 99999
div.style.margin = 0
div.style.padding = 0
div.style.position = 'absolute'
div.style.opacity = 0.9
div.style.backgroundColor = 'gray'
div.style.display = 'none'
div.innerHTML = "<h1 style='text-align: center;vertical-align: middle'>Loading ...</h1>"

var tElement = document.body.getElementsByTagName("table")[0]
document.body.insertBefore(div, tElement)

installReportHandler(document)