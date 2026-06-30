<?php

if(isset($_POST['button']) && isset($_FILES['attachment']))
{
	$from_email		 = 'praveencreativedesigner@gmail.com'; //from mail, sender email address
	$recipient_email = 'praveencreativedesigner@gmail.com'; //recipient email address

	//Load POST data from HTML form
	$sender_name = $_POST["sender_name"]; //sender name
	$reply_to_email = $_POST["sender_email"]; //sender email, it will be used in "reply-to" header
	$subject	 = $_POST["subject"]; //subject for the email
	$message	 = $_POST["message"]; //body of the email

	/*Always remember to validate the form fields like this
	if(strlen($sender_name)<1)
	{
		die('Name is too short or empty!');
	}
	*/
	//Get uploaded file data using $_FILES array
	$tmp_name = $_FILES['attachment']['tmp_name']; // get the temporary file name of the file on the server
	$name	 = $_FILES['attachment']['name']; // get the name of the file
	$size	 = $_FILES['attachment']['size']; // get size of the file for size validation
	$type	 = $_FILES['attachment']['type']; // get type of the file
	$error	 = $_FILES['attachment']['error']; // get the error (if any)

	//validate form field for attaching the file
	if($error > 0)
	{
		die('Upload error or No files uploaded');
	}

	//read from the uploaded file & base64_encode content
	$handle = fopen($tmp_name, "r"); // set the file handle only for reading the file
	$content = fread($handle, $size); // reading the file
	fclose($handle);				 // close upon completion

	$encoded_content = chunk_split(base64_encode($content));
	$boundary = md5("random"); // define boundary with a md5 hashed value

	//header
	$headers = "MIME-Version: 1.0\r\n"; // Defining the MIME version
	$headers .= "From:".$from_email."\r\n"; // Sender Email
	$headers .= "Reply-To: ".$reply_to_email."\r\n"; // Email address to reach back
	$headers .= "Content-Type: multipart/mixed;"; // Defining Content-Type
	$headers .= "boundary = $boundary\r\n"; //Defining the Boundary

	//plain text
	$body = "--$boundary\r\n";
	$body .= "Content-Type: text/plain; charset=ISO-8859-1\r\n";
	$body .= "Content-Transfer-Encoding: base64\r\n\r\n";
	$body .= chunk_split(base64_encode($message));

	//attachment
	$body .= "--$boundary\r\n";
	$body .="Content-Type: $type; name=".$name."\r\n";
	$body .="Content-Disposition: attachment; filename=".$name."\r\n";
	$body .="Content-Transfer-Encoding: base64\r\n";
	$body .="X-Attachment-Id: ".rand(1000, 99999)."\r\n\r\n";
	$body .= $encoded_content; // Attaching the encoded file with email

	$sentMailResult = mail($recipient_email, $subject, $body, $headers);

	if($sentMailResult ){
		echo "<h3>File Sent Successfully.<h3>";
		// unlink($name); // delete the file after attachment sent.
	}
	else{
		die("Sorry but the email could not be sent.
					Please go back and try again!");
	}
}
?>
<!DOCTYPE html>
<html class="no-js" lang="zxx"><head><meta htequiv="Content-Type" content="text/html; charset=UTF-8">

   <meta htequiv="x-ua-compatible" content="ie=edge">
   <title>ANVI CORP USA</title>
   <meta name="description" content="">
   <meta name="viewport" content="width=device-width, initial-scale=1">

   <!-- Place favicon.ico in the root directory -->
   <link rel="shortcut icon" type="image/x-icon" href="assets/img/favicon.png">

   <!-- CSS here -->
   <link rel="stylesheet" href="assets/css/bootstrap.min.css">
   <link rel="stylesheet" href="assets/css/animate.css">
   <link rel="stylesheet" href="assets/css/swiper-bundle.css">
   <link rel="stylesheet" href="assets/css/slick.css">
   <link rel="stylesheet" href="assets/css/spacing.css">
   <link rel="stylesheet" href="assets/css/main.css">
<style>
@media (min-width: 1400px) {
    .container, .container-lg, .container-md, .container-sm, .container-xl, .container-xxl {
        max-width: 1200px;
    }
}
</style>
   </head>

<body>



   <!-- back to top start -->
   <div class="back-to-top-wrapper">
      <button id="back_to_top" type="button" class="back-to-top-btn">
         <svg width="12" height="7" viewBox="0 0 12 7" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path d="M11 6L6 1L1 6" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"></path>
         </svg>
      </button>
   </div>
   <!-- back to top end -->

   <!-- header area start -->
   <header>
      <div id="header-sticky" class="header-area header-transparent header-2">
         <div class="container">
            <div class="header-box">
               <div class="row align-items-center">
                  <div class="col-xl-3 col-lg-6 col-md-6 col-6">
                     <div class="logo">
                        <a href="index.html">
                           <img src="assets/img/logo.png" />
                        </a>
                     </div>
                  </div>
                  <div class="col-xl-9 d-none d-xl-block">
                     <div class="main-menu">
                        <nav id="mobile-menu" class="main-menu-content text-right">
                           <ul class="onepage-menu">

                              <li><a href="#">Home</a></li>
                              <li><a href="#">Services</a></li>
                              <li><a href="#">Industries</a></li>
                              <li><a href="#">About Us </a></li>
                              <li><a href="#">Careers</a></li>
<li><a href="#">Blogs</a></li>

                              <div class="about-btn" style="display: inline; position: relative; top: -2px;">
                                 <a class="btn" href="contact-us.php">Contact Us</a>
                              </div>
                           </ul>

                        </nav>

                     </div>
                  </div>

               </div>
            </div>
         </div>
      </div>
   </header>
   <!-- header area end -->

   <div class="body-overlay"></div>


   <main>

<div class="inner-banner">
  <ul class="circles">
   <li></li>
   <li></li>
   <li></li>
   <li></li>
   <li></li>
   <li></li>
   <li></li>
   <li></li>
   <li></li>
   <li></li>
  </ul>
<h4>Contact Us</h4>
</div>
<section class=" contact-us" style="padding-top:100px; padding-bottom:100px;">



    <div class="container">
      <div class="row">
				<div class="col-md-6">
        <div class="contact-info" style="padding-top:5rem;">
          <div class="contact-info-item">
            <div class="contact-info-icon">
              <i class="fa fa-map-marker"></i>
            </div>

            <div class="contact-info-content">
              <h4>Address</h4>
              <p>7950 Legacy Dr Suite 400,<br/> Plano, TX 75024</p>
            </div>
          </div>

          <div class="contact-info-item">
            <div class="contact-info-icon">
              <i class="fa fa-phone"></i>
            </div>

            <div class="contact-info-content">
              <h4>Phone</h4>
              <p>+1 469-945-4554</p>
            </div>
          </div>

          <div class="contact-info-item">
            <div class="contact-info-icon">
              <i class="fa fa-envelope"></i>
            </div>

            <div class="contact-info-content">
              <h4>Email</h4>
             <p>info@anvicorp.com</p>
            </div>
          </div>
        </div>
</div>
	<div class="col-md-6">
        <div class="contact-form">
          <!--<form action="" id="contact-form">
            <h2>Connect With Us</h2>
            <div class="input-box">
              <input type="text" required="true" name="">
              <span>Full Name</span>
            </div>

            <div class="input-box">
              <input type="email" required="true" name="">
              <span>Email</span>
            </div>

            <div class="input-box">
              <textarea required="true" name=""></textarea>
              <span>Type your Message...</span>
            </div>

            <div class="input-box">
              <input type="submit" value="Send Message" name="">
            </div>
          </form>-->


          <form enctype="multipart/form-data" method="POST" action="">
            <div class="form-group">
              <input class="form-control" type="text" name="sender_name" placeholder="Your Name" required/>
            </div>
            <div class="form-group">
              <input class="form-control" type="email" name="sender_email" placeholder="Recipient's Email Address" required/>
            </div>
            <div class="form-group">
              <input class="form-control" type="text" name="subject" placeholder="Subject"/>
            </div>
            <div class="form-group">
              <textarea class="form-control" name="message" placeholder="Message"></textarea>
            </div>
            <!--<div class="form-group">
              <input class="form-control attachment" type="file" name="attachment" placeholder="Attachment" required/>
            </div>-->
            <div class="form-group">
              <input class="btn apply-btn" type="submit" name="button" value="Submit" />
            </div>
          </form>
        </div>
</div>
      </div>
    </div>
  </section>
   </main>

   <!-- footer-area-start -->
   <footer>
      <div class="footer-area">

         <div class="footer-bottom">
            <div class="container">
               <div class="row">
                  <div class="col-lg-6 col-md-7">
                     <div class="footer-copyright">
                        <span>Copyright © 2024, All Rights Reserved ANVI CORP USA</span>
                     </div>
                  </div>
                  <div class="col-lg-6 col-md-5">
                     <div class="footer-terms">
                        <a href="#">Terms of Use</a>
                        <a href="privacy-policy.html" target="_blank">Privacy Policy</a>
                     </div>
                  </div>
               </div>
            </div>
         </div>
      </div>
   </footer>
   <!-- footer-area-end -->


   <!-- JS here -->
   <script src="assets/js/jquery-3.3.1.min.js"></script>
   <script src="assets/js/bootstrap.min.js"></script>



</body></html>
